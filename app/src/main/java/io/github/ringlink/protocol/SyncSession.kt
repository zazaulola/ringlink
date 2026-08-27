package io.github.ringlink.protocol

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Moves bytes to and from the ring. Implemented over BLE in production, faked in tests. */
interface RingTransport {
    suspend fun write(bytes: ByteArray)
    /** Frames arriving from the notify characteristic. */
    val incoming: Channel<ByteArray>
}

/**
 * Where drained records go.
 *
 * CONTRACT: these methods MUST persist durably before returning. Acknowledging a page advances the
 * ring's single shared resume pointer, and the ring then discards that data — so anything acked but
 * not stored is gone permanently. Testers lost whole nights of history to exactly this.
 */
interface RecordSink {
    suspend fun onEpochs(channel: Int, records: List<EpochRecord>)
    suspend fun onSport(records: List<SportRecord>)
    suspend fun onDescriptor(descriptor: Descriptor)
}

data class SyncStats(
    val epochs: Int = 0,
    val sportIntervals: Int = 0,
    val pages: Int = 0,
    val newestCounter: Long = 0,
    val channelsDrained: Int = 0,
) {
    operator fun plus(o: SyncStats) = SyncStats(
        epochs + o.epochs,
        sportIntervals + o.sportIntervals,
        pages + o.pages,
        maxOf(newestCounter, o.newestCounter),
        channelsDrained + o.channelsDrained,
    )
}

/**
 * Drives one history sync: authenticate, then drain each channel, acknowledging pages so the ring
 * keeps streaming.
 *
 * Deliberately conservative about when it runs: the ACK is a destructive read, and syncing during
 * the night shreds the backlog the ring is still accumulating.
 */
class SyncSession(
    private val macAddress: String,
    private val transport: RingTransport,
    private val sink: RecordSink,
    private val clock: RingClock,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun authenticate(): Boolean {
        transport.write(Opcodes.STATUS_HELLO)
        val challengeFrame = awaitFrame(Opcodes.RESP_STATUS, AUTH_TIMEOUT_MS) ?: return false
        if (challengeFrame.size < 3) return false
        transport.write(RingAuth.replyTo(challengeFrame, macAddress))
        // The ring answers the reply with another 0x81; absence means the answer was rejected.
        return awaitFrame(Opcodes.RESP_STATUS, AUTH_TIMEOUT_MS) != null
    }

    suspend fun syncHistory(channels: IntArray = Opcodes.HISTORY_CHANNELS): SyncStats {
        var total = SyncStats()
        for (channel in channels) {
            total += drainChannel(channel)
        }
        if (total.newestCounter > 0 && clock.calibrate(total.newestCounter, now())) {
            // Anchor moved; timestamps of everything just stored are re-derived on read.
        }
        return total
    }

    private suspend fun drainChannel(channel: Int): SyncStats {
        transport.write(Opcodes.syncOpen(clock.cursorForNow(now()), channel))
        val openAck = awaitFrame(Opcodes.RESP_SYNC_OPEN, OPEN_TIMEOUT_MS)
        // byte[1] == 0xff means the resume pointer is already at the end: nothing to drain.
        if (openAck != null && openAck.size > 1 && (openAck[1].toInt() and 0xff) == 0xff) {
            return SyncStats(channelsDrained = 1)
        }
        transport.write(Opcodes.FETCH)

        var stats = SyncStats(channelsDrained = 1)
        var quietTicks = 0
        var lastDescriptor: ByteArray? = null
        val deadline = System.currentTimeMillis() + CHANNEL_BUDGET_MS

        while (System.currentTimeMillis() < deadline) {
            val frame = withTimeoutOrNull(TICK_MS) { transport.incoming.receive() }
            if (frame == null) {
                quietTicks++
                // A lost 0x50 shouldn't hang the sync, but don't give up before data starts either.
                val cap = if (stats.pages == 0) QUIET_TICKS_BEFORE_DATA else QUIET_TICKS_AFTER_DATA
                if (quietTicks >= cap) break
                continue
            }
            quietTicks = 0
            if (!Frame.isValid(frame)) continue

            when (frame[0].toInt() and 0xff) {
                Opcodes.RESP_PAGE_4C -> {
                    val records = Pages.epochs(frame)
                    // Persist BEFORE acking: the ack is irreversible.
                    sink.onEpochs(channel, records)
                    transport.write(Opcodes.ACK_4C)
                    stats = stats + SyncStats(
                        epochs = records.size,
                        pages = 1,
                        newestCounter = records.maxOfOrNull { it.counter } ?: 0,
                    )
                }
                Opcodes.RESP_PAGE_4D -> {
                    val records = Pages.sport(frame)
                    sink.onSport(records)
                    transport.write(Opcodes.ACK_4D)
                    stats = stats + SyncStats(
                        sportIntervals = records.size,
                        pages = 1,
                        newestCounter = records.maxOfOrNull { it.counter } ?: 0,
                    )
                }
                Opcodes.RESP_PAGE_47 -> {
                    // Sparse perfusion trend: nothing worth storing, but it must still be acked.
                    transport.write(Opcodes.ACK_47)
                    stats = stats + SyncStats(
                        pages = 1,
                        newestCounter = Pages.perfusionCounters(frame).maxOrNull() ?: 0,
                    )
                }
                Opcodes.RESP_HEARTBEAT -> transport.write(Opcodes.HEARTBEAT_ACK)
                Opcodes.RESP_DESCRIPTOR_FETCH, Opcodes.RESP_DESCRIPTOR_QUERY -> {
                    Descriptor.parse(frame)?.let { sink.onDescriptor(it) }
                    // Only re-kick the stream on a genuinely new header, or we loop forever.
                    if (lastDescriptor == null || !frame.contentEquals(lastDescriptor)) {
                        lastDescriptor = frame.copyOf()
                        transport.write(Opcodes.FETCH)
                    }
                }
                Opcodes.RESP_END_OF_HISTORY -> return stats
            }
        }
        return stats
    }

    /** Wait for a specific response id, discarding unrelated frames that arrive first. */
    private suspend fun awaitFrame(responseId: Int, timeoutMs: Long): ByteArray? = try {
        withTimeout(timeoutMs) {
            var result: ByteArray? = null
            while (result == null) {
                val f = transport.incoming.receive()
                if (f.isNotEmpty() && (f[0].toInt() and 0xff) == responseId) result = f
            }
            result
        }
    } catch (_: TimeoutCancellationException) {
        null
    }

    private companion object {
        const val AUTH_TIMEOUT_MS = 4_000L
        const val OPEN_TIMEOUT_MS = 4_000L
        const val TICK_MS = 1_000L
        const val QUIET_TICKS_BEFORE_DATA = 12
        const val QUIET_TICKS_AFTER_DATA = 3
        const val CHANNEL_BUDGET_MS = 180_000L
    }
}
