package io.github.ringlink.protocol

/** A decoded 19-byte device descriptor (`0x10` reply to d0, or `0x87` reply to 07). */
data class Descriptor(
    val batteryPercent: Int,
    val state: Int,
    val steps: Int,
    val skinTempA: Double,
    val skinTempB: Double,
    val batteryMillivolts: Int,
    val caseByte: Int,
) {
    val onCharger: Boolean get() = state == 0x04
    val caseBatteryPercent: Int? get() = if (caseByte == 0xff) null else caseByte and 0x7f
    val caseCharging: Boolean get() = caseByte != 0xff && (caseByte and 0x80) != 0

    companion object {
        const val LENGTH = 19

        fun parse(frame: ByteArray): Descriptor? {
            if (frame.size < LENGTH) return null
            return Descriptor(
                batteryPercent = frame[1].toInt() and 0xff,
                state = frame[2].toInt() and 0xff,
                steps = Frame.u16(frame, 4),
                skinTempA = Frame.u16(frame, 6) / 10.0,
                skinTempB = Frame.u16(frame, 8) / 10.0,
                batteryMillivolts = Frame.u16(frame, 14),
                caseByte = frame[17].toInt() and 0xff,
            )
        }
    }
}

/** How a 23-byte epoch record should be read. Discrimination is structural, not value-based. */
enum class EpochLayout { IDLE, ACTIVITY, SLEEP_VITALS }

/**
 * One 2.5-minute epoch from the `0x4c` history pages — the ring's core health record.
 * Present on both the sleep (0x00) and all-day (0x03) channels with the same schema.
 */
data class EpochRecord(
    val counter: Long,
    val layout: EpochLayout,
    val heartRate: Int?,
    val hrvRmssd: Int?,
    val confidence: Int,
    val respiratoryRate: Double?,
    val spo2: Int?,
    val motion: IntArray,
) {
    companion object {
        const val LENGTH = 23
        const val STEP_SECONDS = 150L

        fun parse(r: ByteArray, off: Int): EpochRecord? {
            if (off + LENGTH > r.size) return null
            fun b(i: Int) = r[off + i].toInt() and 0xff

            val layout = classify(r, off)
            val hrRaw = b(4)
            // Below 30 bpm is the ring's "not measured" sentinel, not a real bradycardia reading.
            val hr = if (layout != EpochLayout.IDLE && hrRaw >= 30) hrRaw else null
            val hrvRaw = b(5)
            val hrv = if (layout == EpochLayout.SLEEP_VITALS && hrvRaw in 1..250) hrvRaw else null
            val rrRaw = b(7)
            val rr = if (layout == EpochLayout.SLEEP_VITALS && rrRaw > 0) rrRaw / 8.0 else null
            // On activity records byte 8 is a layout tag, not a saturation value.
            val spo2Raw = b(8)
            val spo2 = if (layout == EpochLayout.SLEEP_VITALS && spo2Raw in 70..100) spo2Raw else null

            return EpochRecord(
                counter = Frame.u32(r, off),
                layout = layout,
                heartRate = hr,
                hrvRmssd = hrv,
                confidence = b(6),
                respiratoryRate = rr,
                spo2 = spo2,
                motion = IntArray(5) { b(10 + it) },
            )
        }

        private fun classify(r: ByteArray, off: Int): EpochLayout {
            fun b(i: Int) = r[off + i].toInt() and 0xff
            val idleHead = b(4) == 0x05 && b(5) == 0x00 && b(6) == 0x0c && b(7) == 0x00 && b(9) == 0x0a
            if (idleHead && (10..14).all { b(it) == 0x01 } && (15..21).all { b(it) == 0x00 }) {
                return EpochLayout.IDLE
            }
            // 0x11/0x12/0x13 in byte 8 mark an activity epoch whose byte 8 is a tag, not SpO2.
            if (b(8) == 0x12 || b(8) == 0x13 || b(8) == 0x11) return EpochLayout.ACTIVITY
            return EpochLayout.SLEEP_VITALS
        }
    }

    override fun equals(other: Any?): Boolean = other is EpochRecord && other.counter == counter
    override fun hashCode(): Int = counter.hashCode()
}

/** One 10-second interval from the sport history (`0x4d`) pages. */
data class SportRecord(val counter: Long, val heartRate: Int?, val steps: Int) {
    companion object {
        const val LENGTH = 11

        fun parse(r: ByteArray, off: Int): SportRecord? {
            if (off + LENGTH > r.size) return null
            val hr = r[off + 4].toInt() and 0xff
            return SportRecord(
                counter = Frame.u32(r, off),
                heartRate = if (hr in 30..220) hr else null,
                steps = r[off + 5].toInt() and 0xff,
            )
        }
    }
}

/** Splits history pages into fixed-size records. Records never span pages. */
object Pages {
    private const val HEADER = 3
    private const val TRAILER = 1

    fun recordCount(page: ByteArray, recordLength: Int): Int {
        val body = page.size - HEADER - TRAILER
        return if (body <= 0) 0 else body / recordLength
    }

    fun epochs(page: ByteArray): List<EpochRecord> =
        (0 until recordCount(page, EpochRecord.LENGTH)).mapNotNull {
            EpochRecord.parse(page, HEADER + it * EpochRecord.LENGTH)
        }

    fun sport(page: ByteArray): List<SportRecord> =
        (0 until recordCount(page, SportRecord.LENGTH)).mapNotNull {
            SportRecord.parse(page, HEADER + it * SportRecord.LENGTH)
        }

    /**
     * `0x47` pages are a sparse 15-minute optical/perfusion trend (30 x 10-bit samples per record).
     * They are NOT pulse-resolution and cannot yield heart rate; only the timestamps are used.
     */
    fun perfusionCounters(page: ByteArray): List<Long> =
        (0 until recordCount(page, 47)).map { Frame.u32(page, HEADER + it * 47) }

    /** Remaining-record countdown in the page header; 0 marks the last page. */
    fun remaining(page: ByteArray): Int = if (page.size < 3) 0 else Frame.u16(page, 1)
}

/** Live measurement frames (`0x15`). */
object Live {
    /** Heart-rate frames are `15 00 <hr> ...`; values under 30 mean "still warming up". */
    fun heartRate(frame: ByteArray): Int? {
        if (frame.size < 3 || (frame[1].toInt() and 0xff) != 0x00) return null
        val hr = frame[2].toInt() and 0xff
        return if (hr in 30..220) hr else null
    }

    /** SpO2 frames are the long `15 01 ...` variant with saturation at byte 14. */
    fun spo2(frame: ByteArray): Int? {
        if (frame.size < 15 || (frame[1].toInt() and 0xff) != 0x01) return null
        val v = frame[14].toInt() and 0xff
        return if (v in 70..100) v else null
    }
}
