# Streamer

An Android app that keeps a Chetham's StREAM timetable available offline, with
notifications and a home-screen widget.

The school's timetable site needs a live connection and a Windows login every time.
This syncs it to the device so it works without signal, and adds reminders and a
widget the website has no way to provide.

## How it works

The site is a FullCalendar front end over ten AJAX endpoints, each taking `start` and
`end` dates and returning JSON. The app talks to those endpoints directly rather than
scraping or embedding the page.

Three things about the server shape the design:

**Authentication is NTLM**, not a login form — the site is IIS with Windows
Integrated Auth. There is no session cookie to reuse, so every request is
authenticated from the password itself. `net/ntlm/` contains a minimal NTLMv2
implementation, including MD4, because Android's crypto providers do not ship it.

**Data requires a server-side session.** Authenticating is not enough: the feeds
answer for whichever student is selected in the session, set by a separate priming
call. Skipping it returns empty arrays rather than an error, which looks like an
empty timetable rather than a failure.

**Wide date ranges are accepted.** A full sync is ten requests, not ten per week.
Requests are sequential, not parallel — NTLM binds its handshake to a single TCP
connection, so concurrent requests interfere with each other.

## Architecture

| Package | Responsibility |
|---|---|
| `net/` | HTTP client, NTLM handshake, feed parsing |
| `data/` | Room entities, DAOs, migrations, notification rules |
| `sync/` | Sync orchestration and the background worker |
| `notify/` | Alarm scheduling and notification delivery |
| `ui/` | Compose screens; day grouping and clash detection |
| `widget/` | Glance home-screen widget |
| `debug/` | Crash log capture |

Day structure — grouping, clash detection, break markers — lives in
`ui/TimetableSections.kt` and is shared by the app and the widget, so the two cannot
disagree about whether a lesson clashes.

## Building

Requires JDK 21 and the Android SDK (compileSdk 36, minSdk 26).

```bash
./gradlew assembleDebug          # development build
./gradlew assembleRelease        # distributable build
./gradlew testDebugUnitTest      # unit tests
```

Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

### Signing

Release builds are signed from `keystore.properties` in the project root, which is
gitignored. Copy `keystore.properties.example` and fill it in. Without it the build
falls back to the debug key so a fresh checkout still compiles.

Switching between signing keys forces a reinstall — Android refuses to upgrade an app
to a differently-signed build.

## Tests

93 JVM unit tests, no device required. The load-bearing ones:

- **NTLMv2** against the MS-NLMP specification vectors, and MD4 against RFC 1320.
  A mistake here surfaces only as an opaque 401.
- **Feed parsing** against a real capture, including the server's habit of emitting
  PHP notices around its JSON.
- **Clash detection**, where back-to-back lessons must not count as overlapping.
- **Notification rules** — matching, timing, and the scheduling window.

## Privacy

Credentials are encrypted with an Android Keystore key and sent only to
`stream.chethams.com`. Timetable data stays on the device; nothing is sent anywhere
else. Crash logs are written locally and only leave the device if explicitly shared.

Because NTLM re-authenticates on every request, a rejected password could otherwise
be retried until Active Directory locks the account. Sync stops on rejection instead
of retrying, and the background worker treats it as permanent until new credentials
are entered.

`recon/` is gitignored: captures from the live site contain real timetable data.
