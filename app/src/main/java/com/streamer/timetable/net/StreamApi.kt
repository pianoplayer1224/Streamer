package com.streamer.timetable.net

import com.streamer.timetable.data.EventDto
import com.streamer.timetable.data.Feed
import com.streamer.timetable.data.ParticipantDto
import com.streamer.timetable.data.ParticipantsResponse
import com.streamer.timetable.net.ntlm.NtlmAuthenticator
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Raised when the server refused the credentials, as distinct from a transport error. */
class AuthenticationException(message: String) : IOException(message)

/**
 * Client for the StREAM student calendar feeds.
 *
 * The site is a FullCalendar front end over ten AJAX endpoints, each taking `start`
 * and `end` dates and returning a JSON array. Ranges wider than a week are accepted
 * -- verified against a six-week request -- so a full sync is ten requests rather
 * than ten per week.
 *
 * Authentication alone is not enough to get data back. The endpoints answer for
 * whichever student is selected in the *server-side session*, and that selection is
 * made by a separate priming call. See [primeSession].
 */
class StreamApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    credentials: () -> NtlmAuthenticator.Credentials?,
) {

    private val authenticator = NtlmAuthenticator(credentials)

    /**
     * Holds the session cookie for the lifetime of this client.
     *
     * Without a cookie jar OkHttp discards `Set-Cookie` silently, so every request
     * would land in a brand new session with no student selected -- which returns
     * an empty array rather than an error, making the failure look like "no lessons"
     * instead of "not logged in properly".
     */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        // NTLM binds its challenge to a single TCP connection, which HTTP/2's
        // multiplexing and connection coalescing would break. Pin to HTTP/1.1.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .cookieJar(cookieJar)
        .authenticator(authenticator)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Establishes the server-side session, returning the signed-in username.
     *
     * The site's own page does exactly this on load, before requesting any feed:
     * `get_student.asp` reports who the authenticated user is, then
     * `set_student.php` selects that student for the session. Skipping the second
     * call leaves student-specific feeds returning `[]` while global feeds such as
     * term dates still return data -- a partially populated timetable rather than a
     * visible error.
     *
     * This doubles as the authentication preflight: it is small, and if the password
     * is wrong it fails here rather than ten feeds later.
     */
    suspend fun primeSession(): String {
        val username = post("student/ajax/sys/get_student.asp").trim()
        if (username.isEmpty()) {
            throw IOException("Server did not report a username; session not established.")
        }

        // The server logs an "Undefined index: username" notice here and still
        // returns success, so it evidently derives the student from the authenticated
        // identity. The parameter is sent anyway, exactly as the site sends it.
        post(
            "student/ajax/sys/set_student.php",
            FormBody.Builder().add("username", username).build(),
        )

        return username
    }

    /** Fetches one feed across a date range. Requires [primeSession] to have run. */
    suspend fun fetchFeed(feed: Feed, start: LocalDate, end: LocalDate): List<EventDto> {
        val separator = if (feed.path.contains("?")) "&" else "?"
        val path = "student/${feed.path}${separator}start=$start&end=$end"
        val body = get(path)
        val payload = extractJsonArray(body) ?: return emptyList()
        return json.decodeFromString<List<EventDto>>(payload)
    }

    /**
     * Other students in a lesson, with any clash the server knows about.
     *
     * Fetched on demand when a lesson is opened rather than during sync: it is one
     * request per event, so pulling it for every event in the window would turn a
     * ten-request sync into hundreds.
     *
     * Unlike the calendar feeds this returns an object wrapping the array, and its
     * `EventDetails` field is the server's own clash description -- the site renders
     * it with a warning icon.
     */
    suspend fun fetchParticipants(eventId: Long): List<ParticipantDto> {
        val body = get("student/ajax/get-event-other-participants-clashes.php?EventID=$eventId")
        val payload = extractJsonObject(body) ?: return emptyList()
        return json.decodeFromString<ParticipantsResponse>(payload).data
    }

    private fun get(path: String): String = execute(
        Request.Builder().url(url(path)).get().applyAjaxHeaders().build(),
        path,
    )

    private fun post(path: String, body: okhttp3.RequestBody? = null): String = execute(
        Request.Builder()
            .url(url(path))
            .post(body ?: "".toRequestBody(null))
            .applyAjaxHeaders()
            .build(),
        path,
    )

    /** Cache-busting parameter, matching the `_=<millis>` the site appends. */
    private fun url(path: String): String {
        val separator = if (path.contains("?")) "&" else "?"
        return "$baseUrl$path${separator}_=${System.currentTimeMillis()}"
    }

    /** Mirrors the headers the site's own XHRs carry, in case the backend checks them. */
    private fun Request.Builder.applyAjaxHeaders(): Request.Builder = this
        .header("Accept", "*/*")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Referer", "${DEFAULT_BASE_URL}student/")

    private fun execute(request: Request, path: String): String {
        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw AuthenticationException(
                    "Server rejected the credentials. Check your username and password."
                )
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $path")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://stream.chethams.com/"

        /**
         * Extracts the first complete JSON array from a body that may not be clean.
         *
         * This server has been observed emitting PHP notices around the payload --
         * `set_student.php` returns an "Undefined index" warning and then valid JSON.
         * A strict parser dies on the first character.
         *
         * The obvious shortcut, taking everything between the first `[` and the last
         * `]`, is wrong: any trailing junk containing a bracket gets swallowed into
         * the payload. So instead scan forward tracking nesting depth and return at
         * the point the outermost array closes. String literals are tracked too,
         * because a bracket inside a lesson title or a Windows path must not count
         * toward depth.
         *
         * Returns null when there is no complete array, which callers treat as "no
         * events" rather than as an error.
         */
        fun extractJsonArray(body: String): String? = extractJson(body, '[')

        /** As [extractJsonArray], for endpoints returning a wrapper object. */
        fun extractJsonObject(body: String): String? = extractJson(body, '{')

        private fun extractJson(body: String, opening: Char): String? {
            val start = body.indexOf(opening)
            if (start < 0) return null

            var depth = 0
            var inString = false
            var escaped = false

            for (i in start until body.length) {
                val c = body[i]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> {
                        depth--
                        if (depth == 0) return body.substring(start, i + 1)
                    }
                }
            }
            // Unterminated: the response was truncated mid-array.
            return null
        }
    }
}
