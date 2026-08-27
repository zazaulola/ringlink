package io.github.ringlink.protocol

/**
 * The RingConn command set.
 *
 * SAFETY: only commands that are documented or device-verified appear here. Do NOT probe unknown
 * opcodes — a blind sweep of opcode 0x21 bricked a ring during reverse engineering (it needed a
 * charger restart, forget-device and re-pair). If you need a new capability, capture the official
 * app performing it and replay the captured frame.
 */
object Opcodes {

    // --- request ids -------------------------------------------------------------------------
    const val CMD_STATUS = 0x01
    const val CMD_SYNC_OPEN = 0x02
    const val CMD_MODE = 0x06
    const val CMD_FETCH = 0x07
    const val CMD_VIBRATE = 0x0B
    const val CMD_FIND_LED = 0x24
    const val CMD_HEARTBEAT_ACK = 0x91
    const val CMD_POLL = 0x95
    const val CMD_ACK_47 = 0xC7
    const val CMD_ACK_4C = 0xCC
    const val CMD_ACK_4D = 0xCD
    const val CMD_ACK_4E = 0xCE
    const val CMD_STATUS_QUERY = 0xD0

    // --- response ids ------------------------------------------------------------------------
    const val RESP_STATUS = 0x81
    const val RESP_SYNC_OPEN = 0x82
    const val RESP_MODE = 0x86
    const val RESP_DESCRIPTOR_FETCH = 0x87
    const val RESP_DESCRIPTOR_QUERY = 0x10
    const val RESP_HEARTBEAT = 0x11
    const val RESP_LIVE = 0x15
    const val RESP_PAGE_47 = 0x47
    const val RESP_PAGE_4C = 0x4C
    const val RESP_PAGE_4D = 0x4D
    const val RESP_SPORT_LIVE = 0x4E
    const val RESP_END_OF_HISTORY = 0x50

    // --- history channels (each keeps its own independent resume pointer on the ring) ---------
    const val CHANNEL_SLEEP = 0x00
    const val CHANNEL_SPORT = 0x02
    const val CHANNEL_ALL_DAY = 0x03

    /** Draining only the sleep channel loses all daytime SpO2 — sync both. */
    val HISTORY_CHANNELS = intArrayOf(CHANNEL_SLEEP, CHANNEL_ALL_DAY)

    // --- ready-made commands -------------------------------------------------------------------
    val STATUS_HELLO = byteArrayOf(0x01, 0x00, 0x00)
    val FETCH = byteArrayOf(0x07, 0x00, 0x00)
    val STATUS_QUERY = byteArrayOf(0xD0.toByte(), 0x00, 0x00)
    val HEARTBEAT_ACK = byteArrayOf(0x91.toByte(), 0x00, 0x00)
    val POLL = byteArrayOf(0x95.toByte(), 0x00, 0x00)
    val LIVE_HR_MODE = byteArrayOf(0x06, 0x01, 0x00)
    val LIVE_SPO2_MODE = byteArrayOf(0x06, 0x02, 0x00)
    val ACK_47 = byteArrayOf(0xC7.toByte(), 0x00, 0x00)
    val ACK_4C = byteArrayOf(0xCC.toByte(), 0x00, 0x00)
    val ACK_4D = byteArrayOf(0xCD.toByte(), 0x00, 0x00)
    val ACK_4E = byteArrayOf(0xCE.toByte(), 0x00, 0x00)

    /** Find-My-Ring locator LED (device-verified). Lights the ring blue; it does not buzz. */
    val LED_ON = byteArrayOf(0x24, 0x01, 0x00)
    val LED_OFF = byteArrayOf(0x24, 0x00, 0x00)

    /**
     * Gen 3 haptic buzz — captured from the official app on 2026-08-21 and confirmed on-device.
     * opcode 0x0B, sub 0x03, payload 01 64 (on + intensity/duration 0x64).
     * Gen 1/2 have no vibration motor and ignore this.
     */
    val VIBRATE = byteArrayOf(0x0B, 0x03, 0x01, 0x64, 0x00)

    /** The ACK a given page opcode must be answered with, or the stream stalls. */
    fun ackFor(responseId: Int): ByteArray? = when (responseId) {
        RESP_PAGE_47 -> ACK_47
        RESP_PAGE_4C -> ACK_4C
        RESP_PAGE_4D -> ACK_4D
        RESP_SPORT_LIVE -> ACK_4E
        RESP_HEARTBEAT -> HEARTBEAT_ACK
        else -> null
    }

    /** `02 00 <cursor:4 BE> <channel> 01 00` — "drain up to about now" on one channel. */
    fun syncOpen(cursor: Long, channel: Int): ByteArray = byteArrayOf(
        0x02, 0x00,
        (cursor ushr 24).toByte(), (cursor ushr 16).toByte(),
        (cursor ushr 8).toByte(), cursor.toByte(),
        channel.toByte(), 0x01, 0x00,
    )

    /** `01 01 <r0> <r1> <r2> 00` — the SM3 answer to the ring's challenge. */
    fun authResponse(r: ByteArray): ByteArray =
        byteArrayOf(0x01, 0x01, r[0], r[1], r[2], 0x00)
}
