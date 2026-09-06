# RingLink

[![Build](https://github.com/zazaulola/ringlink/actions/workflows/build.yml/badge.svg)](https://github.com/zazaulola/ringlink/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An **offline Android client for the RingConn Gen 3 smart ring**. It talks to the ring directly over
Bluetooth, keeps your health data in a local database, writes it into **Health Connect**, and buzzes
the ring on notifications and incoming calls.

**No cloud. No vendor account. No root.**

## Why

The vendor app is the only way to get data off the ring, and it keeps that data on its own terms.
The ring's Bluetooth protocol turns out to need no cloud key and no account — just the ring's own MAC
address — so an independent client is possible. Health Connect is the on-device health store built
into Android 14+ (Google Fit's APIs are deprecated and supported only to the end of 2026), so that
is where the data belongs.

As a bonus, owning the connection means the ring can be buzzed for **phone notifications and calls** —
something the vendor app deliberately does not do, since it restricts haptics to health reminders.

## What it does

| | |
|---|---|
| **Syncs history** | Heart rate, HRV (RMSSD), SpO₂, respiratory rate and motion, per 2.5-minute epoch, from both the sleep and all-day channels |
| **Live device state** | Battery, step count and two skin-temperature channels, streamed while connected |
| **Skin temperature** | Written to Health Connect as a deviation from your own baseline, measured at the finger |
| **Estimated sleep** | Inferred from stillness and a drop in heart rate, and labelled as an estimate |
| **Writes Health Connect** | `HeartRateRecord`, `HeartRateVariabilityRmssdRecord`, `OxygenSaturationRecord`, `RespiratoryRateRecord`, `StepsRecord` |
| **Buzzes the ring** | On notifications and incoming calls, using the captured Gen 3 vibrate command |
| **Measure on demand** | Tap for a heart-rate or blood-oxygen reading and watch it settle, the way the vendor app's measure button works |
| **Find my ring** | Blinks the locator LED and buzzes, to find a ring you have put down |
| **Today** | Steps against a goal and resting heart rate, from the ring's own activity log |
| **Sleep** | Estimated nights with the vitals measured during each — see below |
| **Export** | Every reading as CSV, with raw ring counters alongside timestamps |
| **Several rings at once** | Keep a spare on the charger and swap when the worn one runs low — every ring stays connected, and only the ones actually being worn are buzzed |
| **Keeps it local** | Everything lands in SQLite first; Health Connect export is a separate, retryable step |

## What it deliberately does not do

- **No sleep stages.** The ring never transmits a hypnogram, so Light/Deep/REM would be invention.
  Sleep *periods* are estimated — see below — but the stages are not.
- **No stress / readiness / recovery scores.** Those are vendor analytics, and Health Connect has no
  record type for them.
- **No pulse waveform.** The ring's `0x47` pages are a sparse 15-minute optical trend (one sample per
  ~30 s), roughly 50× too slow to reconstruct a pulse. They are acknowledged and discarded.

## What the vendor app has that this does not

Deliberately absent, because the ring does not measure them and inventing health data is worse than
omitting it: **sleep stages**, **stress / readiness / sleep scores**, and the **blood-pressure and
vascular trends**, which on the vendor side are modelled rather than measured.

Not yet built, but possible: automatic workout detection and workout sessions, cycle tracking from
temperature, weekly and monthly reports, and sedentary or high-heart-rate reminders.

Two things here have no vendor equivalent at all: **notifications and calls buzzing the ring**, and
the **check-in alarm** that sounds through a silenced phone.

## About the sleep estimate

The ring does not report sleep. It does not even mark when you were asleep — the vendor app infers
that from the same signals RingLink stores. So sleep here is inference, and it is labelled as such
in Health Connect.

Two signals carry it, both checked against real nights: motion sitting at its hardware floor (the
hand genuinely not moving) and a heart rate below your own awake baseline. Requiring the second one
matters — an evening spent still on the sofa satisfies the first, and a motion-only rule reported
one real 5-hour night as nine hours.

Known limits, measured rather than assumed: boundaries land within about 15 minutes of the real
ones, and a night broken by a long waking is reported as two sessions rather than one. Turn it off
under Health Connect if you would rather have nothing than an estimate.

## Requirements

- Android 8+ (built against SDK 36; Health Connect is part of the OS on Android 14+, and a Play
  Store app below that).
- One or more **RingConn Gen 3** rings. A ring already paired with the phone is picked up without
  scanning — Bluetooth bonds live in the system and are shared between apps. A ring the phone has
  never seen is adopted with **Search for a new ring**, which needs the ring out of its charger:
  a charging ring does not advertise and cannot be found. Bluetooth bonds live in the system stack and
  are shared between apps, so the pairing the vendor app created is reused — RingLink never scans.

## Install

Grab `app-release.apk` from the [latest release](../../releases/latest), or build it:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

Then, in the app: pick your ring, grant the permissions it asks for, and press **Sync now**.

## How it works

```
  ┌──────────────┐   BLE (bonded, SM3-authenticated)   ┌────────────────┐
  │ RingConn     │ ◄─────────────────────────────────► │  RingBleClient │
  │ Gen 3        │   service 8327ad99 / write 8327ad98 │  (foreground   │
  └──────────────┘                                     │   service)     │
                                                       └───────┬────────┘
                                       history pages, live frames│
                                                       ┌────────▼────────┐
                                                       │  SyncSession    │
                                                       │  persist ─► ACK │
                                                       └────────┬────────┘
                                                                │
                              ┌─────────────────────────────────▼───────┐
                              │  Room (SQLite) — the durable record      │
                              └─────────────────────────────────┬───────┘
                                                                │
                                                       ┌────────▼────────┐
                                                       │ Health Connect  │
                                                       └─────────────────┘
```

**The load-bearing rule: persist before acknowledging.** Acknowledging a history page advances the
ring's single shared resume pointer and the ring then discards that data — permanently. So every
page is committed to SQLite *before* its ACK goes out. (During the reverse-engineering that this
project builds on, two testers each lost a whole night's data to exactly this.)

Two related safeguards:

- **Auto-sync pauses overnight** (configurable). Draining mid-night shreds the backlog the ring is
  still accumulating; one drain in the morning is both safer and more complete.
- **The epoch anchor is measured, not guessed — and calibration happens once.** The two
  reverse-engineering efforts disagree by exactly 4 hours about the time anchor. Measured on real
  Gen 3 hardware, they turn out to describe *two different fields*: record counters and the vendor
  app's sync-open cursor genuinely live 4 hours apart. Health data is dated from record counters, so
  that is the anchor RingLink ships — see [docs/PROTOCOL.md](docs/PROTOCOL.md). Calibration then runs
  exactly once, because a shifted anchor makes a mis-dated record look like "now" and so can always
  justify shifting further. Counters are stored raw, so re-anchoring re-dates old rows correctly
  instead of corrupting them.

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire format.

## Safety

**Never sweep unknown opcodes at a smart ring.** During the reverse engineering this project builds
on, a blind probe of opcode `0x21` **bricked a ring** — it needed a charger restart, a forget-device
and a re-pair. RingLink only ever sends commands that are documented or were captured from the
vendor app. If you want a new capability, capture the vendor app performing it and replay that frame.

## Privacy

Your data stays on your phone: the ring talks to this app over Bluetooth, the app writes to a local
database and to Health Connect. There is no server, no analytics and no network permission in the
manifest at all. Health Connect stamps records with this app as their origin, and you can inspect or
delete them from Health Connect at any time.

## Credits

Protocol reverse engineering: [OpenCircuit](https://github.com/perezjuanj/OpenCircuit) (MIT) — see
[NOTICE](NOTICE). The Gen 3 vibrate command was captured independently by
[RingVibe](https://github.com/zazaulola/lsposed-ringconn-notification-vibrator).

Not affiliated with, endorsed by, or supported by RingConn.
