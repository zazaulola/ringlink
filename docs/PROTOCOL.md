# RingConn BLE protocol — the parts RingLink uses

Credit: nearly all of this was worked out by
[OpenCircuit](https://github.com/perezjuanj/OpenCircuit) (MIT). This file records what RingLink
depends on, plus what this project verified or added independently.

## Transport

| Role | UUID | Handle |
|------|------|--------|
| Data service | `8327ad99-2d87-4a22-a8ce-6dd7971c0437` | 0x0800 |
| Write (commands) | `8327ad98-2d87-4a22-a8ce-6dd7971c0437` | 0x0802, write **with response** |
| Notify (everything back) | `8327ad97-2d87-4a22-a8ce-6dd7971c0437` | 0x0804, CCCD 0x0805 ← `01 00` |

## Framing — the two directions are not symmetric

- **Commands** are sent **verbatim** and are **not** checksummed; they end in a literal `0x00`.
  Appending an XOR trailer produces frames the ring silently ignores.
- **Responses** are `[respid][payload…][xor]`, `respid = command ^ 0x80`.
- **Exception:** `0x50` end-of-history frames carry **no** XOR trailer. A strict validator rejects
  precisely the frame that signals completion.

## Authentication

Per connection, keyed only on the ring's own MAC — no cloud key, no account:

```
host → 01 00 00
ring → 81 00 <challenge> <xor>
host → 01 01 <r0> <r1> <r2> 00      where r = SM3(V || challenge)[29:32]
                                    and   V = mac[3] ^ mac[4] ^ mac[5]
```

`BluetoothDevice.getAddress()` returns the octets in the order the algorithm expects — no reversal.
An LE bond is also required: unbonded centrals get the handshake but every data command is dropped.

**Verified on Gen 3 (FR05) by this project.** Two auth responses captured from the vendor app
(`85 D7 2F` and `EC 21 1A`) are reproduced by exactly one key byte, confirming the Gen 2 algorithm
carries over unchanged. Covered by unit tests.

## History sync

```
per channel (0x00 sleep, 0x03 all-day, 0x02 sport):
  02 00 <cursor:4 BE> <channel> 01 00     → 82 …   (byte[1]==0xff ⇒ nothing to drain)
  07 00 00                                          kick the stream
  then, for every frame that arrives:
    0x4c page → persist → cc 00 00
    0x47 page → discard → c7 00 00
    0x4d page → persist → cd 00 00
    0x11 heartbeat      → 91 00 00
    0x87/0x10 new header→ 07 00 00
  stop on 0x50, or on repeated silence
```

**The ACK is a destructive read.** It advances the ring's single shared resume pointer; the ring
then drops that page. Persist first, always.

Cursors are `unix_seconds − epoch`, big-endian. The epoch anchor is disputed by 4 hours between
implementations, so RingLink calibrates it at runtime instead of hardcoding either value.

## Records

**`0x4c` epoch — 23 bytes, one per 2.5 minutes** (counter step `0x96` = 150 s):

| Offset | Meaning |
|--------|---------|
| 0..3 | `u32` big-endian counter — a **whole 32-bit number**, not a delimiter plus 24 bits (the leading `0x0c` rolls to `0x0d` in late 2026) |
| 4 | heart rate, bpm (< 30 is the "unmeasured" sentinel) |
| 5 | HRV / RMSSD, ms |
| 6 | confidence |
| 7 | respiratory rate × 8 |
| 8 | SpO₂ % — **or** a layout tag (`0x11`/`0x12`/`0x13`) on activity epochs |
| 9 | marker (~`0x0a`) |
| 10..14 | five 30-second motion counts |
| 15..22 | five 12-bit magnitudes + a 4-bit flag (semantics unknown) |

Layout is discriminated structurally, not by value, so a genuine desaturation is not mistaken for a
sentinel.

**`0x10` / `0x87` descriptor — 19 bytes**, streamed every 30–60 s with no sync session needed:
battery % at 1, state at 2 (`0x04` = charging), steps `u16` at 4, two skin-temperature channels in
0.1 °C at 6 and 8, battery mV at 14, charging-case byte at 17.

**`0x47` pages** are a sparse 15-minute optical/perfusion trend (30 × 10-bit samples per record) —
about 50× too slow for a pulse. Acknowledged, then discarded.

## Device commands

| Meaning | Bytes |
|---------|-------|
| Vibrate (Gen 3) | `0B 03 01 64 00` |
| Find-My-Ring LED on / off | `24 01 00` / `24 00 00` |
| Live HR mode / SpO₂ mode | `06 01 00` / `06 02 00` (needs `d0 00 00` first) |
| Poll live sample | `95 00 00` (no faster than ~2 s) |

The vibrate command is not in any public protocol documentation. It was captured by
[RingVibe](https://github.com/zazaulola/lsposed-ringconn-notification-vibrator) by logging the
vendor app's own writes at the moment it buzzed the ring, then confirmed by replay.

## Not on the wire

Sleep stages, stress/readiness scores and the nightly respiratory summary are **computed by the
vendor app**, not transmitted. Skin temperature appears only in the live descriptor, never in the
bulk history.
