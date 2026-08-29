# Streamer — maintainer guide

Everything a developer needs to work on this project, written for someone who has
never seen it before. `README.md` is the short overview; this is the detail.

**Contents**

1. [Quick start](#1-quick-start)
2. [The server, and why it is unusual](#2-the-server-and-why-it-is-unusual)
3. [File structure](#3-file-structure)
4. [Architecture](#4-architecture)
5. [Decisions that must not be undone](#5-decisions-that-must-not-be-undone)
6. [Tests](#6-tests)
7. [How to change common things](#7-how-to-change-common-things)
8. [Building and signing](#8-building-and-signing)
9. [Toolchain from scratch](#9-toolchain-from-scratch)
10. [The Debug menu](#10-the-debug-menu)
11. [Known limitations](#11-known-limitations)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Quick start

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 93 unit tests, no device needed
./gradlew assembleRelease        # distributable APK
```

APKs land in `app/build/outputs/apk/<variant>/`.

Requires a JDK (21 recommended) and an Android SDK. Create `local.properties`
containing `sdk.dir=/path/to/Android/Sdk`. If you have no toolchain at all, see
[§9](#9-toolchain-from-scratch).

**35 main source files, 7 test files, ~6,700 lines of production Kotlin.**

---

## 2. The server, and why it is unusual

Read this before anything else. Nearly every strange-looking decision in the codebase
traces back to something in this section.

### 2.1 Authentication is NTLM, not a login form

The site is IIS 8.5 with Windows Integrated Authentication:

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: NTLM
WWW-Authenticate: Negotiate
```

There is **no login page and no session cookie**. Every request is authenticated from
the password itself through a three-leg NTLM handshake. Consequences:

- The app must store the real password, not a token. It is encrypted with an AES-GCM
  key held in the Android Keystore (`auth/CredentialStore.kt`).
- Android has no Kerberos credential, so `Negotiate` is ignored and NTLM answered.
- NTLM requires MD4, which Android's crypto providers do not ship. There is a
  hand-written implementation in `net/ntlm/Md4.kt`.
- **Active Directory lockout is a genuine risk.** See [§5.1](#51-the-lockout-guard).

### 2.2 Authenticating is not enough to get data

The feeds answer for whichever student is selected in a **server-side session**. The
website sets that on page load with two calls:

1. `POST student/ajax/sys/get_student.asp` → returns the username as plain text
2. `POST student/ajax/sys/set_student.php` with `username=<name>` → selects them

Skip step 2 and student-specific feeds return `[]` while global feeds (term dates)
still return data — a *partially* populated timetable rather than a visible error.
`StreamApi.primeSession()` performs both, and every sync calls it first.

There are **no cookies involved**: the server keys the selection off the
authenticated Windows identity. A cookie jar is installed defensively, but it is not
what makes this work.

### 2.3 Ten feeds, one schema

The site is a FullCalendar front end over ten AJAX endpoints. Each takes `start` and
`end` and returns a JSON array with an identical shape. **A category is the endpoint
an event came from** — it is not a field on the event.

| Category (`Feed` enum) | Endpoint |
|---|---|
| Timetable | `get-simsevents-student-academic.php` |
| Instrumental lessons | `get-events-student-filter-types.php?EventFilterID=1` |
| Prep | `get-prepevents.php` |
| Other musical activity | `?EventFilterID=2` |
| Academic lessons | `?EventFilterID=3` |
| Other academic activity | `get-events-other-academic-activity.php` |
| Medical | `?EventFilterID=5` |
| Other activity | `?EventFilterID=6` |
| Term dates | `get-events-term-dates.php` |
| Other timetabled | `get-simsevents-student-other-academic.php` |

Enum order is **filter-chip display order**. Reordering is safe — every stored
reference uses `Feed.key` (a string), never the ordinal.

> **Gotcha.** The `EventFilterID` *inside* a record does not always match its
> category. `4` (term dates) and `7` (prep) appear in data but have no filter-type
> endpoint. Category is keyed off the source; the field would misfile them.

> **Naming trap.** "Academic lessons" and "Timetable" are different things. The
> former is the filter-type feed (empty for the account this was built against); the
> latter is the SIMS feed carrying the actual daily lessons. Upcoming switches off
> *Timetable*, not *Academic lessons*.

An eleventh endpoint is fetched on demand, never during sync:

```
student/ajax/get-event-other-participants-clashes.php?EventID=<id>
```

It returns `{"data":[{Forename, Surname, EventDetails, EventStart, EventFinish}]}` —
note the **wrapper object**, unlike the feeds, which is why `extractJsonObject`
exists alongside `extractJsonArray`. `EventDetails` is the server's own clash note.
One request per event, so it loads only when a lesson is opened.

### 2.4 The server emits malformed responses

`set_student.php` has been observed returning a PHP notice *before* its JSON:

```
<br /><b>Notice</b>: Undefined index: username in ... on line <b>41</b><br />
{"status":"success","message":null}
```

`StreamApi.extractJsonArray` therefore scans for a complete JSON value using a
bracket-depth counter that tracks string literals. Taking everything between the
first `[` and the last `]` is **wrong** — trailing junk containing a bracket gets
swallowed into the payload.

### 2.5 Wide date ranges work

Verified against a six-week request returning 131 events. A full sync is therefore
**ten requests**, not ten per week.

Requests are issued **sequentially, never in parallel**: NTLM binds its handshake to
a single TCP connection and concurrent requests fight over it. The client is also
pinned to HTTP/1.1, because HTTP/2 multiplexing breaks challenge-response pairing.

---

## 3. File structure

```
Streamer/
├── gradlew, gradle/                 Gradle wrapper (8.13) — use this, not a system gradle
├── build.gradle.kts                 Plugin versions
├── app/build.gradle.kts             SDK levels, dependencies, signing config
├── local.properties                 sdk.dir — machine-specific, not committed
├── keystore.properties              Signing secrets — GITIGNORED, never commit (§8)
├── keystore.properties.example      Template showing the expected keys
├── app/schemas/                     Room's exported schema; migrations are checked against it
├── icon.svg                         Source artwork for the app icon
├── recon/                           HAR capture used to reverse-engineer the API (gitignored)
└── app/src/
    ├── main/java/com/streamer/timetable/
    │   ├── StreamerApplication.kt   Installs crash reporting before anything else
    │   ├── MainActivity.kt          Compose host: drawer, tabs, screen switching
    │   ├── auth/
    │   │   └── CredentialStore.kt   Keystore-encrypted username / password / domain
    │   ├── net/
    │   │   ├── StreamApi.kt         The ten feeds, session priming, lenient JSON
    │   │   └── ntlm/
    │   │       ├── Md4.kt           RFC 1320 MD4 (Android ships none)
    │   │       ├── Ntlm.kt          Type 1/2/3 messages, NTLMv2
    │   │       └── NtlmAuthenticator.kt   Drives the handshake through OkHttp
    │   ├── data/
    │   │   ├── Event.kt             Feed enum, EventDto, Event entity, DTO→entity mapping
    │   │   ├── EventDao.kt          Queries + replaceWindow reconciliation
    │   │   ├── NotificationRule.kt  Rule entity, matching, trigger times, alert building
    │   │   ├── NotificationRuleDao.kt
    │   │   ├── AppDatabase.kt       Room database, currently version 3
    │   │   ├── Migrations.kt        1→2→3, written against the exported schema
    │   │   └── SampleEvents.kt      Fabricated events for testing prep (debug only)
    │   ├── sync/
    │   │   ├── SyncRepository.kt    Orchestrates a sync; owns all preferences
    │   │   └── SyncWorker.kt        Periodic background sync
    │   ├── notify/
    │   │   ├── AlertScheduler.kt    Rules → alarms; diagnostics
    │   │   ├── AlertReceiver.kt     Posts the notification
    │   │   └── BootReceiver.kt      Re-books alarms after a reboot
    │   ├── widget/
    │   │   ├── TimetableWidget.kt   Glance widget UI
    │   │   ├── WidgetContent.kt     Pure content selection (testable)
    │   │   ├── TimetableWidgetReceiver.kt   Receiver, WidgetUpdater, midnight rollover
    │   │   ├── RefreshAction.kt     Refresh button → enqueues work
    │   │   ├── WidgetSyncWorker.kt  Runs the sync off the broadcast
    │   │   └── WidgetSync.kt        In-flight flag + last outcome
    │   ├── debug/
    │   │   └── CrashReporter.kt     Uncaught exception handler → local files
    │   └── ui/
    │       ├── TimetableSections.kt ★ Pure day-structure logic — read this first
    │       ├── TimetableScreen.kt   List, chips, clash rails, dividers, scroll animation
    │       ├── TimetableViewModel.kt State, preferences, actions
    │       ├── AppDrawer.kt         Sidebar: site links, Options, Debug
    │       ├── LoginScreen.kt       Sign-in
    │       ├── EventDetailSheet.kt  Bottom sheet with participants
    │       ├── NotificationsScreen.kt / RuleEditorScreen.kt
    │       └── Squircle.kt          Superellipse shape for the icon
    ├── main/res/                    Icons, widget metadata, preview layout, strings
    └── test/java/...                93 unit tests, JVM only (§6)
```

★ `TimetableSections.kt` is the single most important file. See [§4.2](#42-shared-day-structure).

---

## 4. Architecture

### 4.1 Data flow

```
stream.chethams.com
        │  NTLM · sequential · HTTP/1.1
        ▼
   StreamApi ──► EventDto ──► Event ──► Room ("events")
                                          │
        ┌─────────────────────────────────┼──────────────────────┐
        ▼                                 ▼                      ▼
  TimetableViewModel              AlertScheduler          TimetableWidget
   (Flow<List<Event>>)             (→ AlarmManager)        (one-shot DAO read)
        ▼
  TimetableScreen
```

**Room is the single source of truth.** The UI never waits on the network; it renders
whatever is stored, and a sync updates the database with the UI following.

Reconciliation matters. `EventDao.replaceWindow` deletes rows *inside the synced
window* that the server did not return. Without it a cancelled lesson would linger
forever — worse than showing nothing, because you would turn up to a lesson that is
not happening. `deleteOutsideWindow` then trims anything outside the configured
range, so shrinking the window actually frees the rows rather than stranding them
where no sync will ever revisit them.

### 4.2 Shared day structure

Both the app list and the widget render from `buildDayRows(events)`, which returns
`List<DayRow>` — each row either a `Lesson` (carrying `ClashState`) or an `Interval`
(a break marker). This exists so clash detection and break placement have **exactly
one definition**. Two implementations would eventually disagree, and a widget
contradicting the app about whether a lesson clashes is worse than neither showing it.

`effectiveNowMillis(today)` is shared for the same reason: it applies the real
time-of-day to whichever date is displayed, so the debug date override behaves
identically in both surfaces.

Rules encoded here:

- **Overlap is strict.** 14:30–15:30 followed by 15:30–16:30 is *not* a clash. This
  timetable is full of back-to-back lessons; treating touching events as clashing
  would paint most of the week red.
- **Zero-length events are instants** ("Term Starts"). They cannot clash, and are
  exempt from "already finished" filtering — they have no end to run past.
- **Filtering happens before clash detection**, so hiding Mus Block resolves the
  clash it caused rather than leaving a red bar with no visible cause.
- **Break markers** sit at 10:30 and 13:00, and only where a real gap exists — a
  lesson spanning 10:30 gets no line drawn through it. Before/after-school rules
  appear only when something falls outside 08:30–16:30.

### 4.3 Tabs

| Tab | Rule |
|---|---|
| This week | Monday–Sunday containing `today` |
| Upcoming | Today onward, excluding anything already finished (judged on **end** time) |
| Prep | The Prep feed only |
| All | Everything stored |

Upcoming additionally switches the Timetable feed **off** on entry and restores its
previous state on exit. That lives in `TimetableViewModel.selectTab`, not in the
filtering, so the chips remain the single truth about what is shown.

### 4.4 State that is not in Room

One `SharedPreferences` file, `streamer_sync`:

| Key | Meaning |
|---|---|
| `last_sync_millis` | When the last successful sync completed |
| `days_behind` / `days_ahead` | Offline window (defaults 14 / 42) |
| `sync_interval_hours` | Background cadence; `0` = off |
| `hide_mus_block` | Hide "Mus Block" sessions (default true) |
| `animate_week_scroll` | Monday→today glide on open |
| `debug_date_override` | ISO date the app pretends is today |
| `sync_attempts` / `_successes` / `_failures` / `_auth_failures` | Counters shown in Debug |
| `sync_last_result` / `sync_last_attempt` | Last outcome, for Debug |
| `widget_sync_started_at` | Widget sync in flight — a *timestamp*, so it cannot latch |
| `widget_last_result` / `_at` | Outcome shown briefly on the widget |

Credentials live separately in `streamer_credentials`, ciphertext only.

### 4.5 Manifest components

| Component | Exported | Purpose |
|---|---|---|
| `.MainActivity` | yes | Launcher entry |
| `.notify.AlertReceiver` | no | Posts a lesson notification |
| `.notify.BootReceiver` | yes | Re-books alarms after reboot |
| `.widget.TimetableWidgetReceiver` | yes | Glance widget host |
| `.widget.WidgetMidnightReceiver` | no | Day rollover and backstop redraws |

Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`,
`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`.

---

## 5. Decisions that must not be undone

Each of these was a real bug. Reverting them reintroduces it.

### 5.1 The lockout guard

`NtlmAuthenticator` returns `null` after a rejected Type 3 rather than retrying, and
`SyncWorker` returns `Result.failure()` — **not** `Result.retry()` — on auth failure.
A worker retrying a bad password on a schedule could lock the student's AD account
out of every school system.

### 5.2 Sequential syncing

See [§2.5](#25-wide-date-ranges-work). Do not parallelise the feed fetches, and do
not remove the HTTP/1.1 pin.

### 5.3 Background work has a ten-second ceiling

Widget button presses arrive as broadcasts, and a background receiver gets roughly
ten seconds before Android may kill the process. A full sync is eleven sequential
NTLM requests and routinely exceeds it. `RefreshAction` therefore only flags and
enqueues; `WidgetSyncWorker` does the work.

That worker's cleanup runs inside `withContext(NonCancellable)`, because
`ExistingWorkPolicy.REPLACE` cancels a running worker and suspend calls in a
cancelled coroutine throw immediately — which previously left "syncing" on screen
indefinitely.

The in-flight marker is a **timestamp, not a boolean**: a boolean set before the work
and cleared in a `finally` stays true forever if the process dies, silently disabling
the button with no way to reset it.

### 5.4 Widgets do not know the date changed

A widget showing "today" will happily display yesterday's lessons all morning.
`updatePeriodMillis` is not a fix — 30-minute floor, suspended in Doze. An exact
alarm at 00:01 refreshes and re-books itself, and `BootReceiver` restores it after a
restart.

`WidgetUpdater.refresh` uses **two paths**: Glance's `updateAll`, plus an explicit
`ACTION_APPWIDGET_UPDATE` broadcast. `updateAll` resolves glance ids itself and
quietly does nothing when that lookup comes back empty, so the broadcast is the
reliable one. It returns a description rather than swallowing errors.

### 5.5 Adaptive icon safe zone is a circle

The launcher guarantees visibility inside the central **circle** of diameter 72 on a
108×108 canvas, not the central square. The foreground tiles are sized so the block's
corners sit within radius 36. `ic_app_icon.xml` (in-app) keeps the original larger
proportions; only `ic_launcher_foreground.xml` is shrunk.

### 5.6 Touch targets

The widget's refresh button is a 48dp box around a 20dp glyph. At 20dp it was missed
often enough to look broken, and a near-miss landed on nothing.

### 5.7 Migrations are real

`fallbackToDestructiveMigration` has been removed deliberately. Events are a
disposable cache, but **notification rules are user-authored and no re-sync can
rebuild them**.

---

## 6. Tests

93 JVM unit tests. No device or emulator required.

```bash
./gradlew testDebugUnitTest
xdg-open app/build/reports/tests/testDebugUnitTest/index.html   # HTML report
```

| Suite | Tests | Covers |
|---|---|---|
| `TimetableSectionsTest` | 31 | Clash clustering, break and school-day dividers, tabs, week grouping, chip counts, scroll anchoring |
| `NotificationRuleTest` | 19 | Rule matching, trigger times, anchors, alert scheduling, weekly collapse |
| `WidgetContentTest` | 15 | Remaining-today selection, clash marking, row caps, debug-clock regression |
| `EventParsingTest` | 10 | Server JSON shapes, PHP-notice tolerance, malformed records, real capture |
| `NtlmTest` | 9 | NTOWFv2 against the MS-NLMP spec vector, Type 3 layout |
| `SampleEventsTest` | 6 | Debug fixtures stay valid |
| `Md4Test` | 3 | RFC 1320 vectors and the canonical NT hash |

**The load-bearing ones.** `NtlmTest.ntowfV2MatchesSpecVector` checks NTOWFv2 against
MS-NLMP §4.2.4.1.1 — if it breaks, every Type 3 message is wrong and the only symptom
is an opaque 401. `Md4Test` uses the published RFC vectors; silent corruption there is
invisible everywhere else.

`EventParsingTest.parsesRealCaptureWhenAvailable` parses `recon/range_test.json` when
present and **skips** otherwise, so the suite passes on a clean checkout. That file
holds real student data and is not committed.

**Writing new tests.** Put logic in pure functions (`TimetableSections.kt`,
`WidgetContent.kt`, `NotificationRule.kt`) and test those. Anything touching Room,
Compose or AlarmManager needs an instrumented test, of which there are none yet.

---

## 7. How to change common things

**Add or reorder a feed** — the `Feed` enum in `data/Event.kt`. Enum order is
filter-chip display order. Reordering is safe; nothing persists the ordinal.

**Break times** — `INTERVALS` in `TimetableSections.kt` (10:30 "Break", 13:00
"Lunch"). School-day edges are `SCHOOL_START` / `SCHOOL_END`.

**Colours** — the day-header ramp and filter chips are `LIGHT_DAYS` / `DARK_DAYS` in
`TimetableScreen.kt`. Category colours are on the `Feed` enum, copied from the site's
own config.

**Sync window defaults** — `DEFAULT_DAYS_BEHIND` / `DEFAULT_DAYS_AHEAD` in
`SyncRepository.kt`; the user-facing options are the `*_CHOICES` lists beside them.

**Scroll animation** — `WEEK_SCROLL_DURATION_MS` in `TimetableScreen.kt`.

**Widget row cap** — `WIDGET_MAX_ROWS` in `WidgetContent.kt`. It guards the
RemoteViews memory budget, not the layout; the list scrolls.

**Database schema** — bump `version` in `AppDatabase.kt`, run
`./gradlew kspDebugKotlin`, read the new JSON in `app/schemas/`, and write a migration
in `Migrations.kt` using **that exact SQL**. Room validates the live database against
the exported schema and throws on any mismatch, so an approximation is a crash on
launch, not a subtle bug.

---

## 8. Building and signing

### Debug

```bash
./gradlew assembleDebug
```

Debug builds are noticeably slower — they are debuggable, which disables ART
optimisations. **Judge animation smoothness only on a release build.**

### Release

Create `keystore.properties` in the project root (gitignored, as are `*.jks`):

```properties
storeFile=/absolute/path/to/streamer-release.jks
storePassword=...
keyAlias=streamer
keyPassword=...
```

> `~` is **not** expanded in a properties file. Use an absolute path, or one relative
> to the project root — otherwise Gradle looks for a directory literally named `~`
> and silently falls back to the debug key.

```bash
./gradlew clean assembleRelease
```

`clean` is not optional after touching signing config: Gradle does not track
`keystore.properties` as a build input, so an incremental build will hand you a
stale, differently-signed APK.

Without the file the build still succeeds, falling back to the debug key.

### Verify before distributing

```bash
export PATH=$JAVA_HOME/bin:$PATH
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

The debug key reads `CN=Android Debug`. Anything else is yours.

`keytool -printcert -jarfile` shows **nothing** for this APK — it only reads v1 JAR
signatures, and with `minSdk 26` the build signs with v2 only. That is correct, not a
fault. Use `apksigner`.

### Signing is a one-way door

Android refuses to upgrade an app to a differently-signed build. Switching keys means
**every user must uninstall**, losing their login, notification rules and cached
timetable. Settle the key before distributing anything, and back up the keystore and
its password — losing them has the same effect.

Minification is deliberately **off** for release. R8 with Room, OkHttp and
kotlinx-serialization needs keep rules that cannot be verified without running the
app, and a wrongly-stripped class fails at runtime rather than at build time.

---

## 9. Toolchain from scratch

No Android Studio required.

```bash
mkdir -p ~/Android/dl && cd ~/Android/dl

# JDK 21 (Temurin)
curl -sSL -o jdk21.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse'
mkdir -p ~/Android/jdk21 && tar -xzf jdk21.tar.gz -C ~/Android/jdk21 --strip-components=1

# Android command-line tools
curl -sSL -o cmdline-tools.zip \
  'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
mkdir -p ~/Android/Sdk/cmdline-tools
unzip -q cmdline-tools.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export JAVA_HOME=~/Android/jdk21
export ANDROID_HOME=~/Android/Sdk

~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-36" "build-tools;35.0.0"

echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

**Versions in use:** Gradle 8.13 · AGP 8.9.1 · Kotlin 2.1.0 · KSP 2.1.0-1.0.29 ·
JDK 21 (jvmTarget 17) · compileSdk & targetSdk 36 · minSdk 26 ·
Compose BOM 2024.12.01 · Room 2.6.1 · OkHttp 4.12.0 · WorkManager 2.10.0 ·
Glance 1.1.1 · kotlinx-serialization 1.7.3 · JUnit 4.13.2

---

## 10. The Debug menu

Sidebar → Debug. Present in release builds too, deliberately — testers need it.

- **Pretend today is** — date override, persisted. Shifts the highlighted week, what
  a sync fetches, and the widget. Shown in the toolbar while active, so a shifted
  date is never mistaken for a real one.
- **Clear stored timetable** — empties the events table.
- **Add sample events** — fabricated prep, a clash, and out-of-hours lessons, because
  the prep feed returns nothing for the account this was built against. Removed by
  the next sync, which is correct reconciliation.
- **Syncs** — attempts / succeeded / failed / rejected, read from preferences so
  background syncs are counted too. `failed` means unreachable; `rejected` means
  credentials refused.
- **Notifications** — permission state, exact-alarm state, scheduled count, next fire
  time. Plus **Test now** (immediate) and **In 1 min** (exercises AlarmManager).
- **Widget** — pushes an update and reports whether it reached a placed widget.
- **Crash logs** — count, share the latest as plain text, clear.
- **Battery / Alarms** — jumps to the system screens governing background work.

---

## 11. Known limitations

Honest list. None of these are theoretical.

- **Notifications have never fired for a real lesson.** The rule logic has 19 tests,
  but end-to-end delivery has only been exercised via the test buttons.
- **Prep is untested against real data.** `get-prepevents.php` returns `[]` for the
  account this was built against; the Prep tab has only ever seen sample rows.
- **Migrations have never run on a real upgrade.** They are written against Room's
  exported schema and verified column-for-column, but no device has performed a 1→3
  migration with real data in it.
- **Only one account's data has ever been seen.** Another student may hit feed shapes
  never parsed. The parser skips bad records rather than failing, so the likely
  symptom is quiet gaps, not crashes.
- **No instrumented tests.** Anything touching Room, Compose or AlarmManager is
  verified by inspection only.
- **Background sync is best-effort.** Doze defers it; aggressive OEM battery managers
  may stop it entirely. Notifications use `setExactAndAllowWhileIdle` and are more
  reliable than syncs. A force-stop clears all alarms until the app is next opened.
- **`allowBackup="false"`** — uninstalling loses rules and login. Deliberate: a backup
  would carry the encrypted password to a device where the Keystore key does not exist.
- **Timezone is hard-coded** to `Europe/London` (`SCHOOL_ZONE`). Correct for a school
  timetable, but a traveller would see school-local times.
- **The clash rail cannot join across rows on the widget.** Widget list items are
  discrete views with no shared canvas.
- **The project is not yet a git repository.** `.gitignore` correctly excludes
  `keystore.properties`, `*.jks` and `recon/`, but none of that takes effect until
  `git init`. Do that before the first commit, now that the signing key is real.

---

## 12. Troubleshooting

**Lessons load but only term dates appear** — session priming failed. Check
`primeSession()` runs before the feeds ([§2.2](#22-authenticating-is-not-enough-to-get-data)).

**"Server rejected the credentials"** — NTLM reached the server and was refused. Try
adding a domain on the sign-in screen. Note that each attempt counts toward AD
lockout, so do not loop on it.

**Widget never updates** — Debug → Widget → *Refresh widget*. `no widgets placed`
means the receiver sees no instances; `broadcast only: <Exception>` means Glance's
`updateAll` threw and the system broadcast carried it.

**Widget shows the wrong day** — the midnight alarm was lost. Opening the app re-arms
it; so does a reboot.

**Notifications never arrive** — Debug → Notifications → *Check*. It separates blocked
permission, denied exact alarms, and simply not being due yet.

**Build says a resource is not found** — check the `res/` subdirectory exists.
`res/layout/` and similar are not created automatically.

**Release APK is still debug-signed** — you did not `clean`, or `storeFile` contains
a `~`. See [§8](#8-building-and-signing).

**Tests pass but the app misbehaves on device** — likely in the untested layer: Room,
Compose, AlarmManager or WorkManager. The 93 tests cover pure logic only.
