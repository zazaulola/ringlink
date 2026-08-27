package io.github.ringlink.protocol

/**
 * SM3 cryptographic hash (GB/T 32905-2016).
 *
 * The RingConn ring authenticates every connection with an SM3-based challenge/response, and SM3 is
 * neither in the JDK nor in Android's stripped Bouncy Castle — so it is implemented here rather than
 * pulling in a crypto dependency for one 90-line function.
 *
 * Validate any change against the standard KAT: SM3("abc") =
 * 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0
 */
object Sm3 {

    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    private const val T0 = 0x79cc4519
    private const val T1 = 0x7a879d8a

    fun hash(input: ByteArray): ByteArray {
        val v = IV.copyOf()
        val padded = pad(input)
        var off = 0
        while (off < padded.size) {
            compress(v, padded, off)
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    /** SHA-2 style padding: 0x80, zeros, then the bit length as a 64-bit big-endian integer. */
    private fun pad(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8
        var padLen = 56 - (msg.size + 1) % 64
        if (padLen < 0) padLen += 64
        val out = ByteArray(msg.size + 1 + padLen + 8)
        msg.copyInto(out)
        out[msg.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[out.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        }
        return out
    }

    private fun compress(v: IntArray, block: ByteArray, off: Int) {
        val w = IntArray(68)
        val w1 = IntArray(64)
        for (i in 0 until 16) {
            val p = off + i * 4
            w[i] = ((block[p].toInt() and 0xff) shl 24) or
                ((block[p + 1].toInt() and 0xff) shl 16) or
                ((block[p + 2].toInt() and 0xff) shl 8) or
                (block[p + 3].toInt() and 0xff)
        }
        for (j in 16 until 68) {
            w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor rotl(w[j - 13], 7) xor w[j - 6]
        }
        for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
        var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]

        for (j in 0 until 64) {
            val t = if (j < 16) T0 else T1
            val a12 = rotl(a, 12)
            val ss1 = rotl(a12 + e + rotl(t, j and 31), 7)
            val ss2 = ss1 xor a12
            val tt1 = ff(j, a, b, c) + d + ss2 + w1[j]
            val tt2 = gg(j, e, f, g) + h + ss1 + w[j]
            d = c
            c = rotl(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl(f, 19)
            f = e
            e = p0(tt2)
        }

        v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
        v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
    }

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)
    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)
    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)
}
