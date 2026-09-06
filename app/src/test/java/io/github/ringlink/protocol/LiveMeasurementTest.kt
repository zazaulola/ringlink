package io.github.ringlink.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ring that answers polls with whatever readings the test hands it. */
private class FakeRing(private val replies: List<ByteArray>) : RingTransport {
    override val incoming = Channel<ByteArray>(capacity = 64)
    val written = mutableListOf<ByteArray>()
    private var next = 0

    override suspend fun write(bytes: ByteArray) {
        written += bytes
        // Answer only polls, the way the ring does.
        if (bytes.contentEquals(Opcodes.POLL) && next < replies.size) {
            incoming.send(replies[next++])
        }
    }

    fun wrote(command: ByteArray) = written.any { it.contentEquals(command) }
}

private fun hrFrame(bpm: Int) = byteArrayOf(0x15, 0x00, bpm.toByte())

class LiveMeasurementTest {

    @Test fun `enters the mode before polling, and leaves it afterwards`() = runTest {
        val ring = FakeRing(listOf(hrFrame(70)))
        LiveMeasurement(ring).measure(LiveMode.HEART_RATE, durationSeconds = 1)

        val order = ring.written
        val statusAt = order.indexOfFirst { it.contentEquals(Opcodes.STATUS_QUERY) }
        val modeAt = order.indexOfFirst { it.contentEquals(Opcodes.LIVE_HR_MODE) }
        val pollAt = order.indexOfFirst { it.contentEquals(Opcodes.POLL) }

        assertTrue("status query must come first", statusAt in 0 until modeAt)
        assertTrue("mode must precede polling", modeAt < pollAt)
        assertTrue("must leave measurement mode", ring.wrote(Opcodes.LIVE_MODE_OFF))
    }

    /**
     * Warm-up and momentary contact loss produce outliers, so the answer is the median rather than
     * the first or the mean — one wild reading must not move it.
     */
    @Test fun `an outlier does not move the result`() = runTest {
        val ring = FakeRing(listOf(hrFrame(62), hrFrame(64), hrFrame(200), hrFrame(63), hrFrame(65)))
        val result = LiveMeasurement(ring).measure(LiveMode.HEART_RATE, durationSeconds = 12)
        assertEquals(64, result)
    }

    @Test fun `reports samples as they arrive`() = runTest {
        val ring = FakeRing(listOf(hrFrame(70), hrFrame(72)))
        val seen = mutableListOf<Int>()
        LiveMeasurement(ring).measure(LiveMode.HEART_RATE, durationSeconds = 6) { seen += it.value }
        assertEquals(listOf(70, 72), seen)
    }

    /** A finger not in contact yields nothing, and that must read as "no reading", not as a number. */
    @Test fun `no usable reading returns null`() = runTest {
        val ring = FakeRing(listOf(hrFrame(5), hrFrame(250)))
        assertNull(LiveMeasurement(ring).measure(LiveMode.HEART_RATE, durationSeconds = 6))
    }

    @Test fun `keeps answering heartbeats mid-measurement`() = runTest {
        val ring = FakeRing(emptyList())
        launch { ring.incoming.send(byteArrayOf(Opcodes.RESP_HEARTBEAT.toByte(), 0x00, 0x00)) }
        LiveMeasurement(ring).measure(LiveMode.HEART_RATE, durationSeconds = 3)
        assertTrue("a heartbeat must still be acked", ring.wrote(Opcodes.HEARTBEAT_ACK))
    }
}
