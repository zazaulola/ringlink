package io.github.ringlink.protocol

/**
 * Per-connection challenge/response the ring requires before it will honour any data command.
 *
 * The only key material is the ring's own BLE MAC — there is no cloud key, app secret or account.
 * Verified against this project's own Gen 3 captures: a single V value reproduces every observed
 * auth response.
 */
object RingAuth {

    /**
     * Parse "F8:79:99:F7:03:AD" into bytes. Android's BluetoothDevice.getAddress() returns display
     * order, which is exactly the order the algorithm expects — no reversal.
     */
    fun macBytes(address: String): ByteArray {
        val parts = address.trim().split(':')
        require(parts.size == 6) { "not a BLE MAC: $address" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }

    /** V = mac[3] xor mac[4] xor mac[5] — the XOR of the last three octets. */
    fun keyByte(mac: ByteArray): Int {
        require(mac.size == 6) { "MAC must be 6 bytes" }
        return (mac[3].toInt() and 0xff) xor (mac[4].toInt() and 0xff) xor (mac[5].toInt() and 0xff)
    }

    /** The three response bytes = last 3 bytes of SM3(V || challenge). */
    fun response(mac: ByteArray, challenge: Int): ByteArray {
        val digest = Sm3.hash(byteArrayOf(keyByte(mac).toByte(), (challenge and 0xff).toByte()))
        return digest.copyOfRange(29, 32)
    }

    /** Build the full `01 01 <r0> <r1> <r2> 00` reply to an `81 00 <challenge> <xor>` frame. */
    fun replyTo(statusFrame: ByteArray, address: String): ByteArray {
        require(statusFrame.size >= 3) { "short status frame" }
        val challenge = statusFrame[2].toInt() and 0xff
        return Opcodes.authResponse(response(macBytes(address), challenge))
    }
}
