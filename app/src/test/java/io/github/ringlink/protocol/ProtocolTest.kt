package io.github.ringlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String): ByteArray =
    s.split(" ", ":").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class Sm3Test {
    @Test fun `standard known-answer vector`() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            Sm3.hash("abc".toByteArray()).hex(),
        )
    }

    @Test fun `multi-block message`() {
        // The second standard vector: "abcd" repeated 16 times = 64 bytes, forcing a second block.
        val msg = "abcd".repeat(16).toByteArray()
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            Sm3.hash(msg).hex(),
        )
    }

    @Test fun `empty input`() {
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            Sm3.hash(ByteArray(0)).hex(),
        )
    }
}

class RingAuthTest {
    /** Documented worked example for the reference ring F8:79:99:F7:03:AD. */
    @Test fun `reference ring challenge responses`() {
        val mac = RingAuth.macBytes("F8:79:99:F7:03:AD")
        assertEquals(0x59, RingAuth.keyByte(mac))
        assertEquals("520be1", RingAuth.response(mac, 0xe5).hex())
        assertEquals("318267", RingAuth.response(mac, 0xb0).hex())
    }

    /**
     * Captured from a real RingConn Gen 3 (firmware FR05): the official app answered challenge 0x89
     * with 85 D7 2F. Confirms the Gen 2 algorithm carries over to Gen 3 unchanged.
     */
    @Test fun `gen3 capture reproduces`() {
        val mac = RingAuth.macBytes("00:00:00:21:F7:49")
        assertEquals(0x9f, RingAuth.keyByte(mac))
        assertEquals("85d72f", RingAuth.response(mac, 0x89).hex())
        assertEquals("ec211a", RingAuth.response(mac, 0x46).hex())
    }

    @Test fun `builds the full reply frame`() {
        val reply = RingAuth.replyTo(hex("81 00 89 00"), "00:00:00:21:F7:49")
        assertArrayEquals(hex("01 01 85 D7 2F 00"), reply)
    }
}

class FrameTest {
    @Test fun `xor trailer validates a real frame`() {
        // Device-verified Find-My-Ring LED acknowledgement.
        assertTrue(Frame.isValid(hex("a4 00 a4")))
        assertFalse(Frame.isValid(hex("a4 00 a5")))
    }

    @Test fun `end of history frame is exempt from the xor rule`() {
        // 0x50 carries no trailer; a strict validator would reject the very completion signal.
        assertTrue(Frame.isValid(hex("50 00 00 15 12 0c 22 aa e4")))
    }

    @Test fun `response id is the command id with the high bit set`() {
        assertEquals(0x81, Frame.responseIdFor(0x01))
        assertEquals(0x15, Frame.responseIdFor(0x95))
        assertEquals(0x4c, Frame.responseIdFor(0xcc))
    }

    @Test fun `counter is a full 32-bit number, not a delimiter plus 24 bits`() {
        // 0x0c is the high byte of the counter and rolls to 0x0d in late 2026.
        assertEquals(0x0c7c3bccL, Frame.u32(hex("0c 7c 3b cc"), 0))
        assertEquals(0x0d000000L, Frame.u32(hex("0d 00 00 00"), 0))
    }
}

class OpcodesTest {
    /** Byte-for-byte match with a sync-open captured from a real Gen 3. */
    @Test fun `sync open matches a captured frame`() {
        assertArrayEquals(
            hex("02 00 0C 7C 3B CC 03 01 00"),
            Opcodes.syncOpen(0x0C7C3BCCL, Opcodes.CHANNEL_ALL_DAY),
        )
        assertArrayEquals(
            hex("02 00 0C 7C 3C BF 02 01 00"),
            Opcodes.syncOpen(0x0C7C3CBFL, Opcodes.CHANNEL_SPORT),
        )
    }

    @Test fun `every page opcode has an acknowledgement`() {
        assertArrayEquals(Opcodes.ACK_4C, Opcodes.ackFor(Opcodes.RESP_PAGE_4C))
        assertArrayEquals(Opcodes.ACK_47, Opcodes.ackFor(Opcodes.RESP_PAGE_47))
        assertArrayEquals(Opcodes.ACK_4D, Opcodes.ackFor(Opcodes.RESP_PAGE_4D))
        assertArrayEquals(Opcodes.HEARTBEAT_ACK, Opcodes.ackFor(Opcodes.RESP_HEARTBEAT))
        assertNull(Opcodes.ackFor(Opcodes.RESP_END_OF_HISTORY))
    }

    /** Captured from the official app the moment it buzzed a Gen 3. */
    @Test fun `vibrate command is the captured frame`() {
        assertArrayEquals(hex("0B 03 01 64 00"), Opcodes.VIBRATE)
    }
}

class RecordsTest {

    private fun epoch(
        counter: Long = 0x0c7c3bccL,
        hr: Int = 68, hrv: Int = 65, conf: Int = 5, rr8: Int = 120, b8: Int = 98,
        motion: IntArray = intArrayOf(1, 2, 3, 4, 5),
    ): ByteArray {
        val r = ByteArray(EpochRecord.LENGTH)
        r[0] = (counter ushr 24).toByte(); r[1] = (counter ushr 16).toByte()
        r[2] = (counter ushr 8).toByte(); r[3] = counter.toByte()
        r[4] = hr.toByte(); r[5] = hrv.toByte(); r[6] = conf.toByte(); r[7] = rr8.toByte()
        r[8] = b8.toByte(); r[9] = 0x0a
        for (i in 0 until 5) r[10 + i] = motion[i].toByte()
        return r
    }

    @Test fun `sleep vitals record decodes every channel`() {
        val rec = EpochRecord.parse(epoch(), 0)!!
        assertEquals(EpochLayout.SLEEP_VITALS, rec.layout)
        assertEquals(0x0c7c3bccL, rec.counter)
        assertEquals(68, rec.heartRate)
        assertEquals(65, rec.hrvRmssd)
        assertEquals(15.0, rec.respiratoryRate!!, 0.001)
        assertEquals(98, rec.spo2)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), rec.motion)
    }

    @Test fun `unworn idle template is recognised and yields no vitals`() {
        val r = ByteArray(EpochRecord.LENGTH)
        r[4] = 0x05; r[5] = 0x00; r[6] = 0x0c; r[7] = 0x00; r[9] = 0x0a
        for (i in 10..14) r[i] = 0x01
        val rec = EpochRecord.parse(r, 0)!!
        assertEquals(EpochLayout.IDLE, rec.layout)
        assertNull(rec.heartRate)
        assertNull(rec.spo2)
    }

    @Test fun `activity record keeps heart rate but byte 8 is a tag not saturation`() {
        val rec = EpochRecord.parse(epoch(b8 = 0x12), 0)!!
        assertEquals(EpochLayout.ACTIVITY, rec.layout)
        assertEquals(68, rec.heartRate)
        assertNull("byte 8 is a layout tag on activity epochs", rec.spo2)
    }

    @Test fun `sub-30 heart rate is the unmeasured sentinel`() {
        assertNull(EpochRecord.parse(epoch(hr = 4), 0)!!.heartRate)
    }

    @Test fun `implausible saturation is rejected`() {
        assertNull(EpochRecord.parse(epoch(b8 = 40), 0)!!.spo2)
    }

    @Test fun `page splits into records and reports the countdown`() {
        val n = 6
        val page = ByteArray(3 + n * EpochRecord.LENGTH + 1)
        page[0] = Opcodes.RESP_PAGE_4C.toByte()
        page[1] = 0x00; page[2] = 0x0c
        for (i in 0 until n) {
            epoch(counter = 0x0c000000L + i * EpochRecord.STEP_SECONDS)
                .copyInto(page, 3 + i * EpochRecord.LENGTH)
        }
        val records = Pages.epochs(page)
        assertEquals(n, records.size)
        assertEquals(0x0c000000L, records.first().counter)
        // Consecutive epochs are exactly 150 s apart.
        assertEquals(EpochRecord.STEP_SECONDS, records[1].counter - records[0].counter)
        assertEquals(12, Pages.remaining(page))
    }

    @Test fun `descriptor decodes battery, steps and skin temperature`() {
        val f = ByteArray(Descriptor.LENGTH)
        f[0] = 0x10; f[1] = 0x4c; f[2] = 0x04
        f[4] = 0x01; f[5] = 0x2c              // 300 steps
        f[6] = 0x01; f[7] = 0x3a              // 31.4 C
        f[8] = 0x01; f[9] = 0x0a              // 26.6 C
        f[14] = 0x0f; f[15] = 0xa1.toByte()   // 4001 mV
        f[17] = 0xff.toByte()
        val d = Descriptor.parse(f)!!
        assertEquals(76, d.batteryPercent)
        assertTrue(d.onCharger)
        assertEquals(300, d.steps)
        assertEquals(31.4, d.skinTempA, 0.001)
        assertEquals(26.6, d.skinTempB, 0.001)
        assertEquals(4001, d.batteryMillivolts)
        assertNull(d.caseBatteryPercent)
    }

    @Test fun `live frames separate heart rate from saturation`() {
        assertEquals(72, Live.heartRate(hex("15 00 48 0a b0 00")))
        assertNull("warm-up sentinel is not a reading", Live.heartRate(hex("15 00 08 0a b0 00")))
        val spo2Frame = ByteArray(16).also { it[0] = 0x15; it[1] = 0x01; it[14] = 0x60 }
        assertEquals(96, Live.spo2(spo2Frame))
        assertNull("heart rate must not be read from a saturation frame", Live.heartRate(spo2Frame))
    }
}

class RingClockTest {
    /**
     * Pinned by a real Gen 3 sync: counter 210141032 was the newest of 1049 records fetched at
     * 2026-08-28T20:34:13Z, and decodes to 20:30:32Z — 3.7 minutes earlier, as it should be.
     */
    @Test fun `epoch anchor matches a real gen3 record`() {
        val clock = RingClock()
        assertEquals(1_787_949_032L, clock.toUnixSeconds(210_141_032L))
    }

    /** The two constants in circulation differ by exactly four hours. */
    @Test fun `the two candidate anchors are four hours apart`() {
        assertEquals(4 * 3600L, RingClock.DEFAULT_EPOCH - RingClock.ALTERNATE_EPOCH)
    }

    /**
     * A whole-hour base shift must be recoverable. Observed for real: the anchor was settled on the
     * earlier constant, then every record started arriving four hours stale, which is the shape of
     * the ring's own clock having been re-set.
     */
    @Test fun `records arriving persistently stale re-open a settled anchor`() {
        val clock = RingClock(RingClock.ALTERNATE_EPOCH, calibrated = true)
        val now = 1_787_949_032L
        val newest = now - 4 * 3600L - RingClock.ALTERNATE_EPOCH
        assertTrue(clock.calibrate(newest, now))
        assertEquals(RingClock.DEFAULT_EPOCH, clock.epoch())
    }

    /**
     * Regression: a mis-parsed page once reported a counter four hours ahead, and calibration
     * happily re-anchored the whole archive to make that counter look like "now". A record from
     * the future is impossible, so a settled anchor must never move backwards for one.
     */
    @Test fun `a settled anchor ignores a bogus future counter`() {
        val clock = RingClock(RingClock.DEFAULT_EPOCH, calibrated = true)
        val now = 1_787_794_967L
        assertFalse(clock.calibrate(clock.cursorForNow(now) + 4 * 3600L, now))
        assertEquals(RingClock.DEFAULT_EPOCH, clock.epoch())
    }

    @Test fun `calibration happens once, not on every sync`() {
        val clock = RingClock()
        val now = 1_787_794_967L
        assertTrue(clock.calibrate(now - (RingClock.DEFAULT_EPOCH + 3600L), now))
        assertTrue(clock.isCalibrated())
        val settled = clock.epoch()
        assertFalse(clock.calibrate(now - (RingClock.DEFAULT_EPOCH + 7200L), now))
        assertEquals(settled, clock.epoch())
    }

    @Test fun `cursor round-trips through the epoch`() {
        val clock = RingClock()
        val now = 1_787_378_188L
        assertEquals(now, clock.toUnixSeconds(clock.cursorForNow(now)))
    }

    @Test fun `calibration corrects a whole-hour anchor error`() {
        val clock = RingClock()
        val now = 1_787_378_188L
        // Ring counters are consistent with an anchor 4 hours later than the default.
        val counter = now - (RingClock.DEFAULT_EPOCH + 4 * 3600L)
        assertTrue(clock.calibrate(counter, now))
        assertEquals(RingClock.DEFAULT_EPOCH + 4 * 3600L, clock.epoch())
        assertEquals(now, clock.toUnixSeconds(counter))
    }

    @Test fun `small skew does not move the anchor`() {
        val clock = RingClock()
        val now = 1_787_378_188L
        val counter = clock.cursorForNow(now) - 120  // two minutes stale
        assertFalse(clock.calibrate(counter, now))
        assertEquals(RingClock.DEFAULT_EPOCH, clock.epoch())
    }

    @Test fun `absurd counters are ignored`() {
        val clock = RingClock()
        assertFalse(clock.calibrate(1, 1_787_378_188L))
        assertEquals(RingClock.DEFAULT_EPOCH, clock.epoch())
    }
}
