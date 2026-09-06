package io.github.ringlink.protocol

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What an on-demand measurement is reading. */
enum class LiveMode(val command: ByteArray, val label: String) {
    HEART_RATE(Opcodes.LIVE_HR_MODE, "Heart rate"),
    SPO2(Opcodes.LIVE_SPO2_MODE, "Blood oxygen"),
}

/** A reading as it arrives, so the UI can show the value settling rather than a frozen spinner. */
data class LiveSample(val mode: LiveMode, val value: Int, val elapsedSeconds: Int)

/**
 * An on-demand measurement, the way the vendor app's "measure now" button works.
 *
 * The ring does not stream readings unsolicited: it has to be put into a measurement mode and then
 * polled. Sensor warm-up means the first seconds return nothing or nonsense, which is why this
 * reports samples as they arrive and settles on a median rather than trusting the first value.
 *
 * Callers must own the transport for the duration — the frame channel has a single consumer, so the
 * idle loop has to be stopped first, exactly as a history sync does.
 */
class LiveMeasurement(private val transport: RingTransport) {

    /**
     * Run a measurement, calling [onSample] for each reading.
     *
     * Returns the median of the readings collected, or null if the ring never produced a usable one
     * — a finger not properly in contact, most often.
     */
    suspend fun measure(
        mode: LiveMode,
        durationSeconds: Int = DEFAULT_DURATION_SECONDS,
        stillConnected: () -> Boolean = { true },
        onSample: (LiveSample) -> Unit = {},
    ): Int? {
        val samples = ArrayList<Int>()
        try {
            // The ring wants a status query before it will accept a mode change.
            transport.write(Opcodes.STATUS_QUERY)
            delay(SETTLE_MS)
            transport.write(mode.command)
            delay(SETTLE_MS)

            val deadline = System.currentTimeMillis() + durationSeconds * 1000L
            var lastPoll = 0L
            while (System.currentTimeMillis() < deadline) {
                if (!stillConnected()) break
                val now = System.currentTimeMillis()
                if (now - lastPoll >= POLL_INTERVAL_MS) {
                    transport.write(Opcodes.POLL)
                    lastPoll = now
                }
                val frame = withTimeoutOrNull(POLL_INTERVAL_MS) { transport.incoming.receive() }
                    ?: continue
                if (frame.isEmpty()) continue

                when (frame[0].toInt() and 0xff) {
                    Opcodes.RESP_LIVE -> {
                        val value = when (mode) {
                            LiveMode.HEART_RATE -> Live.heartRate(frame)
                            LiveMode.SPO2 -> Live.spo2(frame)
                        }
                        if (value != null) {
                            samples += value
                            val elapsed =
                                ((durationSeconds * 1000L - (deadline - now)) / 1000).toInt()
                            onSample(LiveSample(mode, value, elapsed))
                        }
                    }
                    // Keep answering housekeeping so the link stays healthy mid-measurement.
                    Opcodes.RESP_HEARTBEAT -> transport.write(Opcodes.HEARTBEAT_ACK)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Fall through: whatever was collected is still worth reporting.
        } finally {
            stop()
        }

        if (samples.isEmpty()) return null
        // Median, not mean: warm-up and momentary contact loss produce outliers, and one bad
        // reading should not move the answer.
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Leave measurement mode.
     *
     * `06 00 00` is not a captured frame: it is inferred from the mode command's own shape, where
     * the second byte selects the mode, and from the verified Find-My-Ring pair `24 01 00`/`24 00 00`
     * which switches off exactly this way. It is sent best-effort — the risk of an unknown opcode
     * does not apply, since `0x06` itself is documented, and a ring that ignores it simply leaves
     * measurement mode on its own timeout.
     */
    private suspend fun stop() {
        runCatching { transport.write(Opcodes.LIVE_MODE_OFF) }
    }

    companion object {
        const val DEFAULT_DURATION_SECONDS = 30
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SETTLE_MS = 400L
    }
}
