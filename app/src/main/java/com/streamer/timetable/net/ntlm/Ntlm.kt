package com.streamer.timetable.net.ntlm

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal NTLMv2 message construction, per MS-NLMP.
 *
 * stream.chethams.com is IIS advertising `WWW-Authenticate: NTLM` and `Negotiate`.
 * Android has no Kerberos credential to satisfy Negotiate, so we ignore that scheme
 * and answer the NTLM challenge instead -- the server accepts either.
 *
 * Only the three messages needed for HTTP auth are implemented. No signing or
 * sealing is negotiated: HTTPS already protects the channel, and IIS does not
 * require message integrity here. That is why no session key is derived and no
 * MIC is emitted.
 */
internal object Ntlm {

    /** The literal is "NTLMSSP" followed by a NUL -- not a space. */
    private const val SIGNATURE = "NTLMSSP\u0000"

    // Only the flags an HTTP client actually needs. Notably absent are
    // NEGOTIATE_SIGN / NEGOTIATE_SEAL, which would oblige us to derive session keys.
    private const val FLAG_UNICODE = 0x00000001
    private const val FLAG_REQUEST_TARGET = 0x00000004
    private const val FLAG_NTLM = 0x00000200
    private const val FLAG_ALWAYS_SIGN = 0x00008000
    private const val FLAG_EXTENDED_SESSION_SECURITY = 0x00080000

    private const val NEGOTIATE_FLAGS =
        FLAG_UNICODE or FLAG_REQUEST_TARGET or FLAG_NTLM or
            FLAG_ALWAYS_SIGN or FLAG_EXTENDED_SESSION_SECURITY

    /** Milliseconds between the Windows FILETIME epoch (1601-01-01) and the Unix epoch. */
    private const val FILETIME_EPOCH_OFFSET_MS = 11644473600000L

    private const val TYPE3_HEADER_SIZE = 64

    private val random = SecureRandom()

    /** Type 1: "I want to authenticate, here is what I support." Fixed 32 bytes. */
    fun createType1Message(): ByteArray {
        val buf = ByteArray(32)
        SIGNATURE.toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        writeInt(buf, 8, 1)
        writeInt(buf, 12, NEGOTIATE_FLAGS)
        // Domain and workstation field blocks stay zeroed: we supply neither.
        return buf
    }

    /** Material from the server's Type 2 challenge that the Type 3 reply must incorporate. */
    data class Challenge(
        val serverChallenge: ByteArray,
        val targetInfo: ByteArray,
        val flags: Int,
    )

    fun parseType2Message(bytes: ByteArray): Challenge {
        require(bytes.size >= 32) { "NTLM Type 2 message too short: ${bytes.size} bytes" }
        require(String(bytes, 0, 8, Charsets.US_ASCII) == SIGNATURE) {
            "Bad NTLM signature in Type 2 message"
        }
        require(readInt(bytes, 8) == 2) { "Expected NTLM message type 2" }

        val flags = readInt(bytes, 20)
        val serverChallenge = bytes.copyOfRange(24, 32)

        // TargetInfo is optional; servers that predate NTLMv2 omit the field block.
        val targetInfo = if (bytes.size >= 48) {
            val len = readShort(bytes, 40)
            val offset = readInt(bytes, 44)
            if (len > 0 && offset >= 0 && offset + len <= bytes.size) {
                bytes.copyOfRange(offset, offset + len)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        return Challenge(serverChallenge, targetInfo, flags)
    }

    /**
     * Type 3: the authentication itself, carrying the NTLMv2 response.
     *
     * [domain] may be empty. For this server a bare username works, and an empty
     * domain folds into NTOWFv2 as a zero-length string rather than being an error.
     */
    fun createType3Message(
        username: String,
        password: String,
        domain: String,
        workstation: String,
        challenge: Challenge,
    ): ByteArray {
        val clientChallenge = ByteArray(8).also { random.nextBytes(it) }
        val timestamp = (System.currentTimeMillis() + FILETIME_EPOCH_OFFSET_MS) * 10_000
        val ntowfV2 = ntowfV2(username, password, domain)

        // temp = version(2) || zeros(6) || time(8) || clientChallenge(8) || zeros(4)
        //        || targetInfo || zeros(4)
        val temp = ByteArray(28 + challenge.targetInfo.size + 4)
        temp[0] = 1
        temp[1] = 1
        writeLong(temp, 8, timestamp)
        clientChallenge.copyInto(temp, 16)
        challenge.targetInfo.copyInto(temp, 28)

        val ntProof = hmacMd5(ntowfV2, challenge.serverChallenge + temp)
        val ntResponse = ntProof + temp
        val lmResponse =
            hmacMd5(ntowfV2, challenge.serverChallenge + clientChallenge) + clientChallenge

        val domainBytes = domain.toByteArray(Charsets.UTF_16LE)
        val userBytes = username.toByteArray(Charsets.UTF_16LE)
        val hostBytes = workstation.toByteArray(Charsets.UTF_16LE)

        // Payload is laid out in this order; offsets below are derived from it, so the
        // two must stay in step.
        val domainOffset = TYPE3_HEADER_SIZE
        val userOffset = domainOffset + domainBytes.size
        val hostOffset = userOffset + userBytes.size
        val lmOffset = hostOffset + hostBytes.size
        val ntOffset = lmOffset + lmResponse.size
        val totalSize = ntOffset + ntResponse.size

        val msg = ByteArray(totalSize)
        SIGNATURE.toByteArray(Charsets.US_ASCII).copyInto(msg, 0)
        writeInt(msg, 8, 3)

        writeField(msg, at = 12, length = lmResponse.size, offset = lmOffset)
        writeField(msg, at = 20, length = ntResponse.size, offset = ntOffset)
        writeField(msg, at = 28, length = domainBytes.size, offset = domainOffset)
        writeField(msg, at = 36, length = userBytes.size, offset = userOffset)
        writeField(msg, at = 44, length = hostBytes.size, offset = hostOffset)
        writeField(msg, at = 52, length = 0, offset = totalSize)
        writeInt(msg, 60, NEGOTIATE_FLAGS)

        domainBytes.copyInto(msg, domainOffset)
        userBytes.copyInto(msg, userOffset)
        hostBytes.copyInto(msg, hostOffset)
        lmResponse.copyInto(msg, lmOffset)
        ntResponse.copyInto(msg, ntOffset)

        return msg
    }

    /** NTOWFv2 = HMAC_MD5(MD4(UTF16LE(password)), UTF16LE(UPPER(user) + domain)). */
    internal fun ntowfV2(username: String, password: String, domain: String): ByteArray {
        val ntHash = Md4.digest(password.toByteArray(Charsets.UTF_16LE))
        // The username is uppercased; the domain is used verbatim.
        val identity = (username.uppercase() + domain).toByteArray(Charsets.UTF_16LE)
        return hmacMd5(ntHash, identity)
    }

    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacMD5").run {
            init(SecretKeySpec(key, "HmacMD5"))
            doFinal(data)
        }

    /** A field descriptor: length, max length (always equal here), then payload offset. */
    private fun writeField(b: ByteArray, at: Int, length: Int, offset: Int) {
        writeShort(b, at, length)
        writeShort(b, at + 2, length)
        writeInt(b, at + 4, offset)
    }

    private fun writeShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xff).toByte()
        b[at + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun writeInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun writeLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun readShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)
}
