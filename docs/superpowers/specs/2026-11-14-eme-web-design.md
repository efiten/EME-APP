# EME web app (eme.on8ar.eu): design

Date: 14 November 2026. Status: approved, phase 1 built, phase 2 planned (`docs/superpowers/plans/2026-11-15-eme-web-phase2-hb9q.md`).

## Intent

The EME APP (`on5gsdirk-cmd/EME-APP`) is an Android app for EME (moonbounce) operators. iPhone
users cannot install an APK. This web app gives them the same app in Safari, at
`https://eme.on8ar.eu`, installable on the home screen.

Success: an iPhone user can do everything the Android app's latest release (1.1.0 on 14 November 2026) can do, including reading the
HB9Q chat with their own HB9Q login. And it stays that way: a feature or fix in the Android app
reaches the web app in the same pull request.

## Goals

Feature parity with the Android app's latest release (1.1.0 on 14 November 2026) (`EME-APP` README, tab table):

| Tab | What it does |
|---|---|
| Moon | Moon AZ/EL for the user's locator (Meeus, topocentric), local and UTC date and time, moonrise / culmination / moonset, world map with the area where the moon is above the horizon, moon phase, Live CQ stations in their band colour. |
| Deg | EME degradation as calculated by MMMonVHF (50 to 1296 MHz): value now, 30-day graph around a chosen date with moon distance and declination. |
| HB9Q | HB9Q EME chat, read only, per band (12 bands, `ChatMessage.kt` `Hb9qBand`), sub-pages Messages, QRV, Chat CQ (last 60 minutes). "Log out" button. |
| N0UK | N0UK WSJT EME chat (144 MHz), read only, same 3 sub-pages; Chat CQ with frequency, period, mode, locator, moon elevation, greyed out after "stop". |
| Live CQ | W3SZ and N6NU Live CQ spots (MAP65/Q65), merged, filtered per band, with "moon above the station's horizon". |
| Settings | Callsign, locator, language (13: NL, FR, EN, DE, ES, IT, PT, SV, DA, FI, RU, ZH, JA), HB9Q login, Live CQ bands, chat band, degradation band (`SettingsRepository.kt`). |

Also: the dark blue theme of the app, and the version number next to the title.

## Non-goals

- Sending anything to HB9Q or N0UK. Both stay read only.
- Push notifications, offline use of the chats.
- Replacing the Android app.

## Anti-goals (a failure even if it works)

- More load on HB9Q, N0UK or W3SZ than the Android app causes for the same users.
- A login-protected HB9Q chat readable by someone without their own HB9Q login.
- HB9Q passwords stored on the server, in a log, or in a URL.
- Numbers (AZ/EL, degradation, moon times) that differ from the Android app.
- A web app that silently falls behind the Android app.

## Constraints

- **Browsers cannot read HB9Q or N0UK directly.** Neither sends `Access-Control-Allow-Origin`
  (checked 14 November 2026). W3SZ sends `Access-Control-Allow-Origin: *`.
- **Polling limits from `EME-APP/CONTRIBUTING.md`, "Rules for the external services"**:
  HB9Q chat every 20 s, Live CQ every 120 s with at least 30 s between 2 fetches, N0UK at most
  once every 90 s. HB9Q: after failed logins, wait up to 5 minutes (`LoginBackoff.kt`).
- **Honest User-Agent**, like the app's `EME-ON5GS-Android/<version>`.
- **HB9Q login flow** (`Hb9qClient.kt`): GET the start page for a session cookie and the hidden
  field `swordfisch`, POST the login form, POST `rpc/logger/logger_lst.cfm?vBand=<id>` for the
  rows (JSON with HTML rows), GET `?srv=logout`. Every login starts with a fresh cookie jar (#23).
- **iPhone Safari**: works as a home screen web app (manifest, icons, safe-area insets).
- **Hosting**: the front nginx is `nolcluster` (94.130.105.135, Debian 10, nginx 1.14.2, certbot,
  no Docker, no Go or Node; checked 14 November 2026). It serves `rx.on8ar.eu` as static files
  from `/var/www/rx.on8ar.eu` and proxies other sites to back ends, mostly on meshcore-oracle
  (84.235.164.234, ports 3000 to 3004 and 8088). `eme.on8ar.eu` has no DNS record yet.

## Approaches

1. **Static web app + small server, each user with their own HB9Q login. Chosen.**
   The server reads N0UK and Live CQ once for all users (cached for 90 s and 120 s), so those
   sites get fewer requests than from separate apps. For HB9Q the user's password stays on the
   phone and passes the server only at login; the server keeps the HB9Q session in memory.
   Downside: every active device reads the HB9Q chat every 20 s, as the app does.
2. **Same, with 1 shared HB9Q account on the server.** No login for users and the least load on
   HB9Q, but anyone can read a chat HB9Q puts behind a login. Only possible with explicit
   consent from HB9Q. Not chosen.
3. **Share code with Android (Kotlin Multiplatform / Compose for web).** One code base, but a large
   rebuild of Dirk's repository, and Compose in iPhone Safari is not mature. Dropped.

## Where the code lives

Considered:

- **a) In `EME-APP`, folder `web/`. Chosen.** A pull request that changes what users see or how
  something is calculated changes Android and web together. Both test suites read the same
  samples and run in CI on every pull request. Downside: every contributor does the work twice.
- **b) Separate repository with a daily sync issue.** Keeps `EME-APP` unchanged, but the web app
  falls behind until someone ports each change, and nothing enforces it. Not chosen.

## Decided

- Approach 1 (efite, 14 November 2026).
- Scope: everything the Android app's latest release (1.1.0 on 14 November 2026) does, including HB9Q read only (efite).
- Code in `EME-APP/web/`, kept in step with the Android app per pull request (efite, with the
  agreement of ON5GS, 14 November 2026). ON5GS agrees that the Kotlin code is ported to
  JavaScript for this (the repository has no licence file).
- **2 phases** (efite, 14 November 2026). Phase 1: Moon, Deg, N0UK, Live CQ and Settings, without
  the HB9Q tab and without the HB9Q server endpoints. Phase 2: HB9Q, which goes live only after
  ON5GS or efite has agreed it with HB9Q (a server that logs in for web users from 1 IP, under
  its own User-Agent, run by efite).
- **Phase 2 goes ahead** (efite, 15 November 2026): HB9Q is fine with it; the HB9Q chat is already in
  the Android app, so it may be in the web app too.
- **eme.on8ar.eu runs the latest release**, not `main` (efite). The web app is built from the
  release tag, so it shows the same version and the same code as the APK of that release, and
  only changes with a release.
- **Shared expected values** (efite, with the agreement of ON5GS). The first pull request moves
  the hard-coded expected values of the Kotlin tests (`MoonCalculatorTest`, `DegradationTest`,
  `MoonEventsTest` and the other calculation tests) into files under
  `app/src/test/resources/expected/`. The Kotlin tests and the web tests both read those files.

## Keeping Android and web in step

- `CONTRIBUTING.md` and `CLAUDE.md` get a rule: a pull request that changes behaviour a user
  sees (screens, texts, calculations, parsing, polling) changes `app/` and `web/` together.
  Changes that only make sense on one platform (Android Keystore, Safari home screen) say so in
  the pull request.
- The pull request template gets a checkbox for it.
- Both test suites use the samples in `app/src/test/resources/` (real HB9Q, N0UK and W3SZ data)
  and the shared expected values in `app/src/test/resources/expected/`, so a fix with a new
  sample or case fails on the side that lacks it.
- `.github/workflows/test.yml` also runs the web tests (`npm ci && npm test` in `web/`), in the
  same "Unit tests" check that must be green before a merge.
- New texts go into the 13 Android `strings.xml` files; the web app's language files are
  generated from them at build time, so texts cannot drift.
- One version for both: the web app shows `versionName` from `app/build.gradle.kts` of the
  release it was built from.

## Calls made by Claude (open to correction)

- **Frontend**: Vite + plain JavaScript, like CoreDrive RX (rx.on8ar.eu). Calculations ported
  from the Kotlin sources, with the Android app's unit-test cases ported as tests (Vitest), so
  the numbers match.
- **Static files** on nolcluster under `/var/www/eme.on8ar.eu`, like rx.on8ar.eu, with nginx
  proxying `/api/` to the Go server.
- **Server**: Go, 1 static binary, run as a systemd service on nolcluster, listening on
  `127.0.0.1` only; nginx proxies `/api/` to it (efite, 14 November 2026: nolcluster, not a
  container on meshcore-oracle). HB9Q, N0UK and W3SZ see the IP 94.130.105.135. Endpoints:
  - `GET /api/livecq/w3sz` and `GET /api/livecq/n6nu`: cached 120 s, one fetch for all users.
    N6NU sends no `Access-Control-Allow-Origin`, so it has to go through the server anyway.
  - `GET /api/n0uk`: N0UK page, cached 90 s.
  - `POST /api/hb9q/login` (login + password in the body), `GET /api/hb9q/messages?band=<id>`,
    `POST /api/hb9q/logout`. The server keeps 1 HB9Q cookie jar per web session in memory,
    keyed by an `HttpOnly`, `Secure`, `SameSite=Strict` cookie. Messages per session and band
    are fetched at most every 20 s; a request within 20 s gets the cached rows. Same backoff as
    `LoginBackoff.kt`, kept per HB9Q login across the whole server, so a page reload or a new
    session does not reset it. Sessions expire after 30 minutes without a request. Nothing is written to
    disk.
  - Parsing (HTML rows, N0UK page, CSV) happens in the browser; the server passes the raw text.
- **User-Agent** of the server: `EME-ON8AR-Web/<version>`.
- **Password storage on the phone**: in `localStorage`, so the user does not retype it. Safari
  has no equivalent of the Android Keystore; this is the same level as a browser's saved
  password, and the user can clear it with "Clear".
- **Settings** in `localStorage`.
- **Languages**: generated from the 13 `strings.xml` files at build time (see above).
- **World map**: `land110m.txt` from the app (Natural Earth 1:110m, public domain).

## Left to the builder

- Layout per screen within the app's look; the screenshots under `EME-APP/app/build/screenshots`
  (`./gradlew testDebugUnitTest -Pscreenshots`) are the reference.
- Exact cache and session bookkeeping in the server.
- Deployment details (systemd unit, nginx site, TLS certificate with certbot, port number). Deploy by hand at first;
  whether the release workflow also deploys the web app is a later decision.
