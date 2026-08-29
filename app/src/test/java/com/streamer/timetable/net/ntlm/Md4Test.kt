package com.streamer.timetable.net.ntlm

import org.junit.Assert.assertEquals
import org.junit.Test

class Md4Test {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun md4(s: String) = hex(Md4.digest(s.toByteArray(Charsets.US_ASCII)))

    /** Test vectors from RFC 1320, appendix A.5. */
    @Test
    fun rfc1320Vectors() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", md4(""))
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", md4("a"))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", md4("abc"))
        assertEquals("d9130a8164549fe818874806e1c7014b", md4("message digest"))
        assertEquals("d79e1c308aa5bbcdeea8ed63df412da9", md4("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(
            "043f8582f241db351ce627e153e7f0e4",
            md4("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        )
    }

    /** Multi-block input, to exercise the padding path across a 64-byte boundary. */
    @Test
    fun multiBlockInput() {
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            md4("12345678901234567890123456789012345678901234567890123456789012345678901234567890")
        )
    }

    /** The NT hash of a password is MD4(UTF-16LE(password)) -- the NTLM entry point. */
    @Test
    fun ntHashOfKnownPassword() {
        val ntHash = hex(Md4.digest("password".toByteArray(Charsets.UTF_16LE)))
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", ntHash)
    }
}
