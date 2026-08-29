package com.streamer.timetable.net.ntlm

import android.util.Base64
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Drives the three-leg NTLM handshake through OkHttp's authenticator hook.
 *
 * NTLM is unusual among HTTP auth schemes in being *connection oriented*: the
 * server associates the challenge it issued with a specific TCP connection, so all
 * three legs must travel over the same one. Two consequences shape this class and
 * the client that installs it:
 *
 *  - The client must be pinned to HTTP/1.1. HTTP/2 multiplexes streams over a
 *    shared connection and coalesces connections between hosts, which breaks the
 *    server's ability to pair challenge with response.
 *  - Requests must not run concurrently while a handshake is in flight, or a second
 *    request can be handed a connection that is midway through authenticating.
 *
 * The handshake proceeds:
 *   1. Request with no credentials -> 401 with `WWW-Authenticate: NTLM`
 *   2. We send a Type 1 negotiate  -> 401 with `WWW-Authenticate: NTLM <base64>`
 *   3. We send a Type 3 authenticate, carrying the NTLMv2 response
 */
class NtlmAuthenticator(
    private val credentials: () -> Credentials?,
) : Authenticator {

    data class Credentials(
        val username: String,
        val password: String,
        val domain: String,
    )

    /** Reported so callers can distinguish "wrong password" from "network down". */
    @Volatile
    var lastFailureWasRejection: Boolean = false
        private set

    override fun authenticate(route: Route?, response: Response): Request? {
        val creds = credentials() ?: return null

        val challenge = ntlmChallenge(response)
            ?: // Server offered no NTLM scheme at all. Nothing we can do here.
            return null

        val priorHeader = response.request.header(HEADER)

        return when {
            // Leg 1: we have not tried yet, so open with a Type 1 negotiate.
            priorHeader == null -> {
                lastFailureWasRejection = false
                val type1 = Ntlm.createType1Message()
                response.request.newBuilder()
                    .header(HEADER, "NTLM " + type1.toBase64())
                    .build()
            }

            // Leg 2: our Type 1 was answered with a challenge, so complete it.
            challenge.isNotEmpty() && priorHeader.isNegotiateOnly() -> {
                val type2 = Ntlm.parseType2Message(Base64.decode(challenge, Base64.NO_WRAP))
                val type3 = Ntlm.createType3Message(
                    username = creds.username,
                    password = creds.password,
                    domain = creds.domain,
                    workstation = WORKSTATION,
                    challenge = type2,
                )
                response.request.newBuilder()
                    .header(HEADER, "NTLM " + type3.toBase64())
                    .build()
            }

            // We already sent a full Type 3 and were still refused. Stop.
            //
            // This is the account-lockout guard. Active Directory lockout policies
            // count failed authentications, and a client that retried a bad password
            // on every sync could lock the student out of the school's systems
            // entirely -- far worse than a stale timetable. Returning null aborts
            // the request so the failure surfaces as "re-enter your password".
            else -> {
                lastFailureWasRejection = true
                null
            }
        }
    }

    /**
     * A bare `NTLM` header means we sent Type 1 and nothing more; once a Type 3 has
     * gone out the header is far longer, since it carries the NTLMv2 response.
     */
    private fun String.isNegotiateOnly(): Boolean {
        val payload = substringAfter("NTLM ", "").trim()
        if (payload.isEmpty()) return false
        return try {
            val decoded = Base64.decode(payload, Base64.NO_WRAP)
            decoded.size >= 12 && decoded[8].toInt() == 1
        } catch (e: Exception) {
            false
        }
    }

    /** Extracts the base64 payload from the NTLM challenge, ignoring Negotiate. */
    private fun ntlmChallenge(response: Response): String? {
        val headers = response.headers(WWW_AUTHENTICATE)
        if (headers.isEmpty()) return null
        for (value in headers) {
            val trimmed = value.trim()
            if (trimmed.equals("NTLM", ignoreCase = true)) return ""
            if (trimmed.startsWith("NTLM ", ignoreCase = true)) {
                return trimmed.substring(5).trim()
            }
        }
        return null
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val HEADER = "Authorization"
        const val WWW_AUTHENTICATE = "WWW-Authenticate"

        // Sent as the client's machine name. The server records it but does not
        // validate it, so a constant is fine and avoids leaking the device name.
        const val WORKSTATION = "ANDROID"
    }
}
