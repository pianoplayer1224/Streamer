package com.streamer.timetable.net.ntlm

/**
 * MD4 (RFC 1320).
 *
 * Android's JCE providers do not ship MD4, but NTLM needs it: the NT hash of a
 * password is defined as MD4(UTF-16LE(password)). There is no way around it, so
 * the digest is implemented here rather than pulling in a heavyweight SMB library
 * for one function.
 *
 * MD4 is cryptographically broken. It is used here only because the NTLM wire
 * protocol mandates it -- never use it for anything else.
 */
internal object Md4 {

    private val ROUND1_SHIFT = intArrayOf(3, 7, 11, 19)
    private val ROUND2_SHIFT = intArrayOf(3, 5, 9, 13)
    private val ROUND3_SHIFT = intArrayOf(3, 9, 11, 15)

    private val ROUND2_ORDER = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val ROUND3_ORDER = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)

    fun digest(input: ByteArray): ByteArray {
        val padded = pad(input)
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476

        val x = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                x[i] = (padded[j].toInt() and 0xff) or
                    ((padded[j + 1].toInt() and 0xff) shl 8) or
                    ((padded[j + 2].toInt() and 0xff) shl 16) or
                    ((padded[j + 3].toInt() and 0xff) shl 24)
            }

            val aa = a; val bb = b; val cc = c; val dd = d

            // Round 1: F(x,y,z) = (x AND y) OR (NOT x AND z)
            for (i in 0 until 16) {
                val shift = ROUND1_SHIFT[i % 4]
                when (i % 4) {
                    0 -> a = rotl(a + f(b, c, d) + x[i], shift)
                    1 -> d = rotl(d + f(a, b, c) + x[i], shift)
                    2 -> c = rotl(c + f(d, a, b) + x[i], shift)
                    else -> b = rotl(b + f(c, d, a) + x[i], shift)
                }
            }

            // Round 2: G(x,y,z) = majority, with the sqrt(2) constant
            for (i in 0 until 16) {
                val k = ROUND2_ORDER[i]
                val shift = ROUND2_SHIFT[i % 4]
                when (i % 4) {
                    0 -> a = rotl(a + g(b, c, d) + x[k] + 0x5a827999, shift)
                    1 -> d = rotl(d + g(a, b, c) + x[k] + 0x5a827999, shift)
                    2 -> c = rotl(c + g(d, a, b) + x[k] + 0x5a827999, shift)
                    else -> b = rotl(b + g(c, d, a) + x[k] + 0x5a827999, shift)
                }
            }

            // Round 3: H(x,y,z) = XOR, with the sqrt(3) constant
            for (i in 0 until 16) {
                val k = ROUND3_ORDER[i]
                val shift = ROUND3_SHIFT[i % 4]
                when (i % 4) {
                    0 -> a = rotl(a + h(b, c, d) + x[k] + 0x6ed9eba1, shift)
                    1 -> d = rotl(d + h(a, b, c) + x[k] + 0x6ed9eba1, shift)
                    2 -> c = rotl(c + h(d, a, b) + x[k] + 0x6ed9eba1, shift)
                    else -> b = rotl(b + h(c, d, a) + x[k] + 0x6ed9eba1, shift)
                }
            }

            a += aa; b += bb; c += cc; d += dd
            offset += 64
        }

        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { i, v ->
            out[i * 4] = (v and 0xff).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xff).toByte()
        }
        return out
    }

    private fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
    private fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
    private fun h(x: Int, y: Int, z: Int) = x xor y xor z
    private fun rotl(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))

    /** Append 0x80, zero-pad to 56 mod 64, then the bit length as little-endian u64. */
    private fun pad(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        var padLen = 56 - (input.size % 64)
        if (padLen <= 0) padLen += 64
        val out = ByteArray(input.size + padLen + 8)
        input.copyInto(out)
        out[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[out.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
        }
        return out
    }
}
