# EME web app, phase 1: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A web app at `https://eme.on8ar.eu` with the Moon, Deg, N0UK and Live CQ tabs and Settings of the Android app v1.1.0, kept in step with the app in the same repository. No HB9Q (phase 2).

**Architecture:** Static Vite app in `web/` (plain JavaScript), calculations and parsers ported 1:1 from the Kotlin sources and tested against the same samples and expected values. A Go server in `web/server/` (systemd on nolcluster, `127.0.0.1` only) fetches W3SZ, N6NU and N0UK once for all users and returns the raw text; nginx serves the static files and proxies `/api/`.

**Tech Stack:** Node 24, Vite, Vitest, jsdom; Go 1.26 (standard library only); nginx 1.14 + certbot on nolcluster (Debian 10).

**Spec:** `docs/superpowers/specs/2026-11-14-eme-web-design.md`

## Global Constraints

- Source of truth is the Kotlin code at the commit the task starts from. A port keeps names, constants and behaviour; a deviation needs a line in the pull request saying why.
- Parity target: Android app **v1.1.0** (`8771a75`), not 1.0.0: includes N6NU as a second Live CQ source (#51) and the Deg calendar with best days (#50).
- Phase 1 has no HB9Q tab, no HB9Q endpoints, no password field.
- Polling (CONTRIBUTING.md): Live CQ (W3SZ and N6NU together) every 120 s, at least 30 s between 2 fetches; N0UK at most once every 90 s. These limits apply to the server's requests to those sites, for all users together.
- User-Agent of the server: `EME-ON8AR-Web/<versionName>`.
- Server listens on `127.0.0.1:5200` only.
- No hard-coded colours in components: CSS custom properties, values from `ui/theme/Theme.kt` and `cq/BandColors.kt`.
- Texts only from the 13 `strings.xml` files; no text typed into `web/`.
- One pull request per task, branched from `upstream/main`, in task order. Each pull request runs `./gradlew testDebugUnitTest` and, from task 2 on, `npm test` in `web/` and `go test ./...` in `web/server/`.
- Git rules from `CLAUDE.md`: stage files by name, no force-push, never commit on `main`.

## Review Focus

1. **No or invalid locator in Settings.** Moon, Deg, N0UK moon elevation and Live CQ "moon up" must show the existing "set your locator" text, never `NaN` or a crash. Text key `no_locator`. Test in task 7 (`settings.js`) and task 10.
2. **Upstream site down or slow.** The server answers within 15 s with the last good copy plus `X-Upstream-Error`; the page keeps the last list and shows the error line, as the app does. Test in task 8.
3. **Many browsers at once when the cache expires.** Exactly 1 request goes to W3SZ/N6NU/N0UK. Test in task 8 (100 parallel requests, 1 upstream hit).
4. **N0UK page charset.** The page is not always UTF-8; the server decodes using the `charset` from `Content-Type` (as `N0ukStore.kt` does) and returns UTF-8. Test in task 8 with an ISO-8859-1 body containing `é`.
5. **Local time and DST in Safari.** Local times use the browser time zone; Deg days are UTC noon as in `Degradation.daily`. Test in task 3 and task 11 with `TZ=America/New_York` across 1 November 2026 (US DST end).

---

### Task 1: Shared expected values for the calculation tests (Kotlin only)

**Files:**
- Create: `app/src/test/resources/expected/moon_azel.csv`, `moon_events.csv`, `moon_phase.csv`, `degradation_daily.csv`, `maidenhead.csv`
- Create: `app/src/test/java/be/on5gs/eme/ExpectedCsv.kt`
- Modify: `moon/MoonCalculatorTest.kt` (`azEl_matchesAstropyReference`), `moon/MoonEventsTest.kt` (`moonDown_nextPass`, `moonUp_showsNextRise`), `degradation/DegradationTest.kt` (`matchesMmmonvhfToday`, `closeToMmmonvhfServerList`, `moonPhase`), `location/MaidenheadTest.kt` (the `toLatLon` cases)

**Interfaces:**
- Produces: `object ExpectedCsv { fun read(name: String): List<Map<String, String>> }`: reads `expected/<name>.csv` from the test classpath; first line is the header; `#` lines and blank lines are skipped; values are trimmed strings. Web tests (task 2) read the same files.

CSV columns (one row per existing case, values copied from the current tests, tolerance per row):
- `moon_azel.csv`: `locator,utc,az,el,tol_deg` (7 rows, `tol_deg` 0.05)
- `moon_events.csv`: `locator,now_utc,is_up,next_rise_utc,next_set_utc,culmination_utc,culmination_el,rise_az,set_az` (2 rows; empty cell = not asserted)
- `moon_phase.csv`: `utc,illuminated,tol,name_index` (4 rows)
- `degradation_daily.csv`: `date,lat,lon,band,tsys,deg_db,tol,condition` (`2026-11-10` tol 0.05 with `POOR`; the 5 server-list dates tol 0.35, condition empty)
- `maidenhead.csv`: `locator,lat,lon` (JO21SC, JO21, FN20, QF22, AA00aa, RR99XX)

- [ ] **Step 1:** Write the 5 CSV files and `ExpectedCsv.kt`; rewrite the listed tests to loop over `ExpectedCsv.read(...)` with the same assertions and messages.
- [ ] **Step 2:** Change one value in `moon_azel.csv` by 1 degree; run `./gradlew testDebugUnitTest --tests '*MoonCalculatorTest*'`; expected: FAIL. Revert.
- [ ] **Step 3:** Run `./gradlew testDebugUnitTest`; expected: all pass, same test count as before.
- [ ] **Step 4:** Commit `tests: read the calculation reference values from shared CSV files`; PR body says the files will also be read by the web tests.

### Task 2: `web/` project, Maidenhead and MoonCalculator, CI

**Files:**
- Create: `web/package.json` (scripts `dev`, `build`, `test` = `vitest run`), `web/vite.config.js`, `web/index.html`, `web/src/main.js` (empty shell for now)
- Create: `web/src/lib/maidenhead.js`, `web/src/lib/moon-calculator.js`
- Create: `web/test/expected.js`, `web/test/maidenhead.test.js`, `web/test/moon-calculator.test.js`
- Modify: `.github/workflows/test.yml`: second job `web` (setup-node 24, `npm ci && npm test` in `web/`); job names `test` and `web` both required by the "Unit tests" workflow
- Modify: `.gitignore`: `web/node_modules/`, `web/dist/`

**Interfaces:**
- Consumes: CSV files from task 1.
- Produces:
  - `readExpected(name) -> Array<Object<string,string>>` in `web/test/expected.js` (reads `../app/src/test/resources/expected/<name>.csv`, same rules as `ExpectedCsv`)
  - `normalize(s) -> string`, `isValid(s) -> boolean`, `toLatLon(s) -> {latDeg, lonDeg} | null` (port of `Maidenhead.kt`)
  - `julianDay(ms)`, `eclipticPosition(jde) -> {lonDeg, latDeg, distanceKm}`, `apparentSiderealTimeDeg(jd)`, `position(latDeg, lonDeg, ms, heightM = 0) -> {azimuthDeg, elevationDeg, distanceKm, raDeg, decDeg, isAboveHorizon}`, `quickElevationDeg(sub, latDeg, lonDeg)`, `subLunarPoint(ms)`, `norm`, `sinD`, `cosD`, `DELTA_T_SECONDS = 69.2` (port of `MoonCalculator.kt`, all exported names as in Kotlin, camelCase)

- [ ] **Step 1:** Write `maidenhead.test.js` (rows of `maidenhead.csv`, plus the `isValid`/`normalize` cases of `MaidenheadTest.kt`) and `moon-calculator.test.js` (rows of `moon_azel.csv` with `tol_deg`, plus `julianDay(2000-01-01T12:00Z) == 2451545.0`, the ecliptic and equatorial values of `MoonCalculatorTest.kt`, `aboveHorizonFlag`).
- [ ] **Step 2:** `cd web && npm test`; expected: FAIL (modules missing).
- [ ] **Step 3:** Port `Maidenhead.kt` and `MoonCalculator.kt`.
- [ ] **Step 4:** `npm test`; expected: PASS. `./gradlew testDebugUnitTest`; expected: PASS.
- [ ] **Step 5:** Commit `web: start the web app with Maidenhead and the moon position`.

### Task 3: MoonEvents and MoonPhase

**Files:** Create `web/src/lib/moon-events.js`, `web/src/lib/moon-phase.js`, `web/test/moon-events.test.js`, `web/test/moon-phase.test.js`

**Interfaces:**
- Consumes: `position`, `eclipticPosition`, `julianDay`, `DELTA_T_SECONDS`, `norm`, `sinD`, `cosD` (task 2).
- Produces: `computeEvents(latDeg, lonDeg, nowMs) -> {isUp, nextRise, culmination, nextSet}` with each event `{utcMillis, azimuthDeg, elevationDeg} | null`; `phaseAt(ms) -> {illuminated, ageDeg, waxing, nameIndex}`.

- [ ] **Step 1:** Tests: rows of `moon_events.csv` (time tolerance 60 s, culmination 180 s, az 0.2), rows of `moon_phase.csv`, the range test of `MoonPhaseTest.kt`, `subLunarPoint_hasMoonInZenith`, `quickElevation_matchesFullCalculation`. Review Focus 5: one test run with `process.env.TZ = 'America/New_York'` asserting the same `utcMillis` as without.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `MoonEvents.kt` and `MoonPhase.kt`.
- [ ] **Step 4:** `npm test`; expected: PASS.
- [ ] **Step 5:** Commit `web: moonrise, culmination, moonset and moon phase`.

### Task 4: Degradation

**Files:** Create `web/src/lib/degradation.js`, `web/test/degradation.test.js`

**Interfaces:**
- Consumes: `position` (task 2).
- Produces: `EME_BANDS` (array of `{id, label, mhz, defaultTsys}`, ids `B50 B144 B222 B432 B902 B1296`, values from `EmeBand`), `CONDITIONS` (`EXCELLENT GOOD FAIR POOR VERY_POOR`), `tsky144(raHours)`, `rangeFactorDb(km)`, `conditionOf(db)`, `computeDegradation(ms, latDeg, lonDeg, band, tsysK) -> {degDb, tskyK, tskyMinK, rangeFactorDb, distanceKm, condition, decDeg}`, `daily(startIsoDate, days, latDeg, lonDeg, band, tsysK) -> Array<[isoDate, Result]>` (UTC noon per day), `bestDaysOfMonth(year, month1to12, latDeg, lonDeg, band, tsysK, count = 4) -> Set<isoDate>` (stable sort, earliest wins ties).

- [ ] **Step 1:** Tests: rows of `degradation_daily.csv`; `conditions` and `higherBandsHaveLessSkyNoise` from `DegradationTest.kt`; both tests of `BestDaysOfMonthTest.kt`.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `Degradation.kt` (the `TSKY_144` table copied as-is).
- [ ] **Step 4:** `npm test`; expected: PASS.
- [ ] **Step 5:** Commit `web: EME degradation and best days`.

### Task 5: Live CQ parsing (W3SZ, N6NU, merge, select)

**Files:** Create `web/src/lib/livecq.js`, `web/test/livecq.test.js`

**Interfaces:**
- Produces: `BANDS` (array of `{id, label, minMhz, maxMhz}` from `Band`, `B1CM.maxMhz = Infinity`), `bandForFrequency(mhz) -> id | null`, `parseW3sz(csv) -> Spot[]`, `parseN6nu(json) -> Spot[]`, `mergeSpots(w3sz, n6nu) -> Spot[]`, `selectSpots(spots, bandIds: Set, nowMs, maxAgeMinutes) -> Spot[]`. `Spot = {utcMillis, callsign, grid, freqMhz, band, mode, db, spotter, spotterGrid, sources: Set<'W3SZ'|'N6NU'>, dt, app}`.

- [ ] **Step 1:** Tests: port every case of `LiveCqParserTest.kt`, `N6nuParserTest.kt`, `LiveCqMergeTest.kt`, reading `livecq_sample.csv` and `n6nu_spots.json` from `../app/src/test/resources/`.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `LiveCqParser.kt`, `N6nuParser.kt`, `LiveCqSpot.kt` (`Band`).
- [ ] **Step 4:** `npm test`; expected: PASS.
- [ ] **Step 5:** Commit `web: parse and merge W3SZ and N6NU Live CQ spots`.

### Task 6: N0UK parsing

**Files:** Create `web/src/lib/n0uk.js`, `web/test/n0uk.test.js`

**Interfaces:**
- Produces: `parseN0uk(html, nowMs) -> Message[]`, `kindOf(msg) -> 'CQ'|'STOP'|'OTHER'`, `parseCq(msg) -> Cq | null`, `recentCqs(messages, nowMs, minutes) -> Cq[]`, `recentWriters(messages, myCallsign) -> Message[]`. Field names as `N0ukMessage` / `N0ukCq`.

- [ ] **Step 1:** Tests: every case of `N0ukParserTest.kt`, on `n0uk_jt65emeA.html`.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `N0ukParser.kt`.
- [ ] **Step 4:** `npm test`; expected: PASS.
- [ ] **Step 5:** Commit `web: parse the N0UK chat`.

### Task 7: Texts, version and settings

**Files:**
- Create: `web/scripts/strings.mjs` (build step: all `app/src/main/res/values*/strings.xml` to `web/src/generated/strings.json`, `{ "<lang>": { "<name>": "<text>" } }`, `values` = `en`), `web/src/i18n.js`, `web/src/settings.js`
- Modify: `web/package.json` (`prebuild`, `pretest`, `predev` run `strings.mjs`), `web/vite.config.js` (`define: { __APP_VERSION__ }` from `versionName` in `app/build.gradle.kts`)
- Test: `web/test/strings.test.js`, `web/test/settings.test.js`

**Interfaces:**
- Produces: `t(name, ...args) -> string` (Android `%1$s` / `%2$d` placeholders, `\'` unescaped), `setLanguage(code)`, `LANGUAGES` (13 codes, from `AppLanguage.options`); `loadSettings() -> {locator, callsign, cqBands: Set, degBand, language}` with defaults `""` (locator), `""` (callsign, as the app since #55), `{B23CM}`, `B1296` (as `SettingsRepository.degBand`), browser language if one of the 13 else `en`; `saveSettings(partial)`; `locatorLatLon(settings) -> {latDeg, lonDeg} | null`.

- [ ] **Step 1:** Tests: every language has every key of `en` with the same placeholders (as `TranslationsTest.kt`); `t('chatcq_title', '144', 3)` in `nl` gives the Dutch sentence with 144 and 3; `loadSettings()` on empty `localStorage` gives the defaults; `localStorage` that throws on access still gives the defaults; Review Focus 1: `locatorLatLon` is `null` for `""`, `"XX99"`, `"JO2"`.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `npm test`; expected: PASS. `strings.json` is generated, not committed (`.gitignore`).
- [ ] **Step 5:** Commit `web: texts from strings.xml, version, settings`.

### Task 8: Go server

**Files:**
- Create: `web/server/go.mod` (module `eme-web`, go 1.26), `web/server/main.go`, `web/server/upstream.go`, `web/server/upstream_test.go`, `web/server/handlers_test.go`

**Interfaces:**
- Produces HTTP endpoints (all `GET`, `Content-Type` passed as UTF-8 text, `Cache-Control: no-store`):
  - `/api/livecq/w3sz`: body of `https://w3sz.com/export_livecq3.php`
  - `/api/livecq/n6nu`: body of `https://livecq.n6nu.org/api/spots?limit=200&offset=0&minutes=60&path=eme&lists=0`
  - `/api/n0uk`: body of `https://www.chris.org/cgi-bin/jt65emeA`, decoded with the charset of its `Content-Type`
  - `/api/version`: `{"version":"<versionName>"}`
  - headers `X-Fetched-At` (Unix ms of the copy) and, when the last attempt failed, `X-Upstream-Error`
- Flags: `-listen 127.0.0.1:5200`, `-version <versionName>`.
- `type source struct { url string; maxAge time.Duration; minInterval time.Duration }`: W3SZ and N6NU `maxAge` 120 s and `minInterval` 30 s; N0UK `maxAge` 90 s and `minInterval` 90 s. Upstream timeout 15 s. One fetch at a time per source; concurrent callers wait for it.

- [ ] **Step 1:** Tests with `httptest.Server` as upstream: second request within `maxAge` does not hit upstream; after `maxAge` it does; never 2 upstream hits within `minInterval`; User-Agent is `EME-ON8AR-Web/<version>`; Review Focus 2: upstream returns 500 or sleeps 20 s, response still 200 with the previous body and `X-Upstream-Error`, first ever failure gives 502; Review Focus 3: 100 parallel requests after expiry, exactly 1 upstream hit; Review Focus 4: upstream ISO-8859-1 body with `é` comes back as UTF-8 `é`.
- [ ] **Step 2:** `cd web/server && go test ./...`; expected: FAIL.
- [ ] **Step 3:** Implement with the standard library (`net/http`, `golang.org/x` not allowed; charset: ISO-8859-1 and windows-1252 decoded by hand, anything else passed through).
- [ ] **Step 4:** `go test ./...`; expected: PASS. Add `go test ./...` in `web/server` to the `web` job of `test.yml`.
- [ ] **Step 5:** Commit `web: server that fetches Live CQ and N0UK once for all users`.

### Task 9: App shell, theme, tabs, Settings

**Files:** Create `web/src/theme.css` (custom properties from `Theme.kt` and `BandColors.kt`), `web/src/app.js` (title with `v<version>`, tab bar Moon · Deg · N0UK · Live CQ, settings icon), `web/src/screens/settings.js`, `web/public/manifest.webmanifest`, `web/public/icon-192.png`, `web/public/icon-512.png` (from `ic_launcher_foreground.xml`), `web/test/app.test.js`

**Interfaces:**
- Consumes: `t`, `loadSettings`, `saveSettings`, `LANGUAGES`, `isValid`, `normalize`.
- Produces: `mountApp(root)`, screen contract `{ mount(el, ctx), unmount() }` with `ctx = { settings, t, now: () => number }`; screens poll only while mounted and while `document.visibilityState === 'visible'`.

- [ ] **Step 1:** jsdom tests: title shows `v` + `__APP_VERSION__`; 4 tabs in the order above with texts from `strings.json`; Settings saves a valid locator through `normalize` and refuses `"XX99"` with `t('locator_invalid')`; language switch re-renders texts.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Implement; layout matches `maan.png` (top bar, tab bar) from `./gradlew testDebugUnitTest -Pscreenshots`; safe-area insets as in rx.on8ar.eu.
- [ ] **Step 4:** `npm test`; expected: PASS. `npm run build` succeeds.
- [ ] **Step 5:** Commit `web: app shell, tabs and settings`.

### Task 10: Moon tab with world map

**Files:** Create `web/src/lib/label-placer.js`, `web/src/lib/world-map.js` (projection, terminator, land rings from `app/src/main/assets/land110m.txt` copied at build time), `web/src/screens/moon.js`, `web/test/label-placer.test.js`, `web/test/moon-screen.test.js`

**Interfaces:**
- Consumes: tasks 2, 3, 5, 7, 9; Live CQ data via `fetchLiveCq()` from task 12 (until then the screen takes `spots` as an optional `ctx` field).
- Produces: `placeLabels(labels, dotRadius, gap, width, height, obstacles) -> Placement[]` (port of `LabelPlacer.place`), `drawWorldMap(canvas, {nowMs, home, spots})`.

- [ ] **Step 1:** Tests: every case of `LabelPlacerTest.kt`; moon screen shows AZ and EL with 1 decimal for JO21SC at `2026-11-07T10:00:00Z` (182.4 and 25.1) and rise/culmination/set lines; Review Focus 1: with locator `""` it shows `t('no_locator')` and no numbers.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `LabelPlacer.kt`, `WorldMap.kt`, `MoonScreen.kt` (clock updates every second, events every minute as in the app).
- [ ] **Step 4:** `npm test`; expected: PASS. Compare against `maan.png` by eye; note differences in the PR.
- [ ] **Step 5:** Commit `web: moon tab with world map`.

### Task 11: Deg tab with graph and calendar

**Files:** Create `web/src/screens/degradation.js`, `web/src/lib/deg-calendar.js`, `web/test/deg-calendar.test.js`, `web/test/degradation-screen.test.js`

**Interfaces:**
- Consumes: task 4, 7, 9.
- Produces: `dayLabels(isoDates, todayIso, centerIndex) -> Array<[index, label]>`, `firstDayOfWeek(locale, systemCountry) -> 1..7 (ISO)`.

- [ ] **Step 1:** Tests: every case of `DayLabelsTest.kt` and `FirstDayOfWeekTest.kt`; the screen shows the value now with 1 decimal and the condition text; best days marked in the calendar for November 2026 equal `bestDaysOfMonth(2026, 11, …)`; Review Focus 5: with `TZ=America/New_York` the calendar day of 1 November 2026 maps to UTC noon of 2026-11-01.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `DegradationScreen.kt` and `DegCalendar.kt` (graph as SVG).
- [ ] **Step 4:** `npm test`; expected: PASS.
- [ ] **Step 5:** Commit `web: Deg tab with graph and calendar`.

### Task 12: Live CQ and N0UK tabs, data polling

**Files:** Create `web/src/data.js`, `web/src/screens/livecq.js`, `web/src/screens/n0uk.js`, `web/test/data.test.js`, `web/test/livecq-screen.test.js`, `web/test/n0uk-screen.test.js`

**Interfaces:**
- Consumes: tasks 2, 5, 6, 7, 9 (N0UK moon elevation uses `position`), server endpoints of task 8.
- Produces: `fetchLiveCq() -> Promise<{spots, fetchedAt, error}>` (both endpoints, merged), `fetchN0uk() -> Promise<{messages, fetchedAt, error}>`; refresh every 120 s (Live CQ) and 90 s (N0UK) while the tab is visible; manual refresh not more often than every 30 s.

- [ ] **Step 1:** Tests with a stubbed `fetch`: on network error the previous list stays and the error text shows (Review Focus 2, browser side); no second request within 30 s on repeated refresh taps; Live CQ list for `livecq_sample.csv` + `n6nu_spots.json` matches `selectSpots` with the saved bands; N0UK Messages, QRV and Chat CQ sub-pages render the sample with "stop" greyed out.
- [ ] **Step 2:** `npm test`; expected: FAIL.
- [ ] **Step 3:** Port `CqScreen.kt`, `N0ukScreen.kt`; the moon tab gets its spots from `fetchLiveCq()`.
- [ ] **Step 4:** `npm test`; expected: PASS. Manual check with `npm run dev` and the Go server running locally (`go run . -listen 127.0.0.1:5200 -version dev`, Vite proxy `/api` to it).
- [ ] **Step 5:** Commit `web: Live CQ and N0UK tabs`.

### Task 13: Rules, release build and deployment files

**Files:**
- Modify: `CONTRIBUTING.md`, `CLAUDE.md` (rule: behaviour users see changes `app/` and `web/` together; platform-only changes say so), `.github/pull_request_template.md` (checkbox "Android and web changed together, or the PR says why not"), `.claude/skills/release/SKILL.md` (release notes cover web; after the release, deploy with `web/deploy/deploy-release.sh`)
- Modify: `.github/workflows/release.yml`: after the APK, build `web/dist` and `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build` the server, zip both as `eme-web-vX.Y.Z.zip`, attach to the release
- Create: `web/deploy/eme-web.service` (systemd, `User=www-data`, `EnvironmentFile=/etc/default/eme-web` with `EME_VERSION=<versionName>` written by the deploy script, `ExecStart=/opt/eme-web/eme-web -listen 127.0.0.1:5200 -version ${EME_VERSION}`, `Restart=on-failure`), `web/deploy/nginx-eme.on8ar.eu.conf` (root `/var/www/eme.on8ar.eu`, cache rules as `rx.on8ar.eu`, `location /api/ { proxy_pass http://127.0.0.1:5200; }`), `web/deploy/deploy-release.sh` (arguments: release tag; env `EME_DEPLOY_HOST`, `EME_DEPLOY_KEY`; downloads `eme-web-<tag>.zip` from the GitHub release, copies `dist/` to `/var/www/eme.on8ar.eu` and the binary to `/opt/eme-web/`, writes `/etc/default/eme-web`, restarts the service, exits non-zero unless `https://eme.on8ar.eu/api/version` shows the tag's version)

- [ ] **Step 1:** Run the release workflow on a fork with a throwaway key and version `1.1.1-test1` (as for #37); expected: release has `EME-APP.apk` and `eme-web-v1.1.1-test1.zip`.
- [ ] **Step 2:** `bash -n web/deploy/deploy-release.sh`; `nginx -t` on a copy of the site file is done in task 14.
- [ ] **Step 3:** Commit `web: same-PR rule, release build and deployment files`.

### Task 14: First deployment (manual, needs efite)

Not a pull request. Every server change is confirmed by efite first (global rule).

- [ ] **Step 1 (efite):** DNS A record `eme.on8ar.eu` to `94.130.105.135`.
- [ ] **Step 2:** On nolcluster: user/dirs `/opt/eme-web`, `/var/www/eme.on8ar.eu`; install the nginx site; `nginx -t`; reload; `certbot --nginx -d eme.on8ar.eu`.
- [ ] **Step 3:** Install and enable `eme-web.service`; `deploy-release.sh` with the first release that contains the web zip.
- [ ] **Step 4:** Verify: `curl -s https://eme.on8ar.eu/api/version` shows the release version; `curl -sI https://eme.on8ar.eu/api/livecq/w3sz` 200 with `X-Fetched-At`; the page title shows the same `v` as the APK; open on an iPhone, add to the home screen.
