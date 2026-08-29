package com.streamer.timetable.net.ntlm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtlmTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun readShort(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    /**
     * MS-NLMP section 4.2.4.1.1. This is the single most important vector in the
     * file: if NTOWFv2 is wrong, every Type 3 message is wrong, and the only symptom
     * would be an opaque 401 from the server.
     */
    @Test
    fun ntowfV2MatchesSpecVector() {
        val result = Ntlm.ntowfV2(username = "User", password = "Password", domain = "Domain")
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hex(result))
    }

    /** The username is uppercased before hashing, so case must not matter. */
    @Test
    fun ntowfV2IsCaseInsensitiveInUsername() {
        val lower = Ntlm.ntowfV2("user", "Password", "Domain")
        val upper = Ntlm.ntowfV2("USER", "Password", "Domain")
        assertArrayEquals(lower, upper)
    }

    /** The domain is NOT uppercased, so its case must matter. */
    @Test
    fun ntowfV2IsCaseSensitiveInDomain() {
        val a = Ntlm.ntowfV2("User", "Password", "Domain")
        val b = Ntlm.ntowfV2("User", "Password", "DOMAIN")
        assertTrue("domain case should change the hash", !a.contentEquals(b))
    }

    /** An empty domain is the configuration this app actually ships with. */
    @Test
    fun ntowfV2AcceptsEmptyDomain() {
        val result = Ntlm.ntowfV2("raylin", "hunter2", "")
        assertEquals(16, result.size)
    }

    @Test
    fun type1MessageIsWellFormed() {
        val msg = Ntlm.createType1Message()
        assertEquals(32, msg.size)
        assertEquals("NTLMSSP\u0000", String(msg, 0, 8, Charsets.US_ASCII))
        assertEquals(1, readInt(msg, 8))
    }

    /** Builds a synthetic Type 2 the way IIS would, then checks we read it back. */
    @Test
    fun type2MessageParsesChallengeAndTargetInfo() {
        val serverChallenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val targetInfo = byteArrayOf(9, 9, 9, 9, 9, 9)
        val msg = ByteArray(48 + targetInfo.size)

        "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII).copyInto(msg, 0)
        msg[8] = 2
        serverChallenge.copyInto(msg, 24)
        msg[40] = targetInfo.size.toByte()
        msg[42] = targetInfo.size.toByte()
        msg[44] = 48
        targetInfo.copyInto(msg, 48)

        val parsed = Ntlm.parseType2Message(msg)
        assertArrayEquals(serverChallenge, parsed.serverChallenge)
        assertArrayEquals(targetInfo, parsed.targetInfo)
    }

    /** A short Type 2 with no target-info block must degrade, not crash. */
    @Test
    fun type2MessageToleratesMissingTargetInfo() {
        val msg = ByteArray(32)
        "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII).copyInto(msg, 0)
        msg[8] = 2
        val parsed = Ntlm.parseType2Message(msg)
        assertEquals(0, parsed.targetInfo.size)
        assertEquals(8, parsed.serverChallenge.size)
    }

    /**
     * Every field descriptor in a Type 3 message points at a (offset, length) slice
     * of the payload. If the declared offsets and the actual byte layout disagree,
     * the server rejects the message -- so assert they agree.
     */
    @Test
    fun type3MessageOffsetsMatchPayloadLayout() {
        val challenge = Ntlm.Challenge(
            serverChallenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            targetInfo = byteArrayOf(7, 7, 7, 7),
            flags = 0,
        )
        val msg = Ntlm.createType3Message(
            username = "raylin",
            password = "hunter2",
            domain = "",
            workstation = "ANDROID",
            challenge = challenge,
        )

        assertEquals("NTLMSSP\u0000", String(msg, 0, 8, Charsets.US_ASCII))
        assertEquals(3, readInt(msg, 8))

        // Each descriptor must address bytes that exist inside the message.
        for (at in intArrayOf(12, 20, 28, 36, 44, 52)) {
            val len = readShort(msg, at)
            val maxLen = readShort(msg, at + 2)
            val offset = readInt(msg, at + 4)
            assertEquals("len and maxLen differ at $at", len, maxLen)
            assertTrue("field at $at overruns message", offset + len <= msg.size)
        }

        // The username field should round-trip back to what we passed in.
        val userLen = readShort(msg, 36)
        val userOffset = readInt(msg, 36 + 4)
        val user = String(msg, userOffset, userLen, Charsets.UTF_16LE)
        assertEquals("raylin", user)

        val hostLen = readShort(msg, 44)
        val hostOffset = readInt(msg, 44 + 4)
        assertEquals("ANDROID", String(msg, hostOffset, hostLen, Charsets.UTF_16LE))

        // LMv2 is always exactly 24 bytes: a 16-byte HMAC plus the 8-byte client nonce.
        assertEquals(24, readShort(msg, 12))

        // NTLMv2 is a 16-byte proof followed by the blob, which embeds target info.
        assertEquals(16 + 28 + challenge.targetInfo.size + 4, readShort(msg, 20))
    }

    /** The client challenge is random, so two messages must never be identical. */
    @Test
    fun type3MessagesUseFreshClientChallenge() {
        val challenge = Ntlm.Challenge(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ByteArray(0), 0)
        val a = Ntlm.createType3Message("u", "p", "", "H", challenge)
        val b = Ntlm.createType3Message("u", "p", "", "H", challenge)
        assertTrue("client challenge should be random per message", !a.contentEquals(b))
    }
}
