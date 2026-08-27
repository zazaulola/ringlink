package io.github.ringlink.protocol

/**
 * Wire framing for the RingConn command channel.
 *
 * The two directions are NOT symmetric, which is the classic porting bug:
 *  - Commands (host -> ring) are sent VERBATIM and are NOT checksummed; they end in a literal 0x00.
 *    Never append an XOR trailer to a command — the ring silently ignores such frames.
 *  - Responses (ring -> host) are `[respid][payload...][xor]`, where xor is the XOR of every
 *    preceding byte and respid == commandId xor 0x80.
 *
 * Exception: `0x50` end-of-history frames carry NO XOR trailer (their last byte is part of the
 * cursor). A strict validator would reject exactly the frame that signals sync completion.
 */
object Frame {

    const val RESP_FLAG = 0x80

    /** Opcodes whose frames legitimately have no XOR trailer. */
    private val NO_TRAILER = setOf(Opcodes.RESP_END_OF_HISTORY)

    fun xorTrailer(bytes: ByteArray, endExclusive: Int = bytes.size): Byte {
        var acc = 0
        for (i in 0 until endExclusive) acc = acc xor (bytes[i].toInt() and 0xff)
        return acc.toByte()
    }

    /** True when a received frame's XOR trailer checks out (or the opcode is exempt). */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        val id = frame[0].toInt() and 0xff
        if (id in NO_TRAILER) return true
        if (frame.size < 2) return false
        return xorTrailer(frame, frame.size - 1) == frame[frame.size - 1]
    }

    /** The response id the ring will use for a given command id. */
    fun responseIdFor(commandId: Int): Int = commandId xor RESP_FLAG

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    fun u16(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xff) shl 8) or (bytes[off + 1].toInt() and 0xff)

    /**
     * Read a 32-bit big-endian counter. Note the leading byte is genuinely part of the number —
     * it is NOT a record delimiter, and it rolls over (0x0c -> 0x0d) in late 2026.
     */
    fun u32(bytes: ByteArray, off: Int): Long =
        ((bytes[off].toLong() and 0xff) shl 24) or
            ((bytes[off + 1].toLong() and 0xff) shl 16) or
            ((bytes[off + 2].toLong() and 0xff) shl 8) or
            (bytes[off + 3].toLong() and 0xff)
}
