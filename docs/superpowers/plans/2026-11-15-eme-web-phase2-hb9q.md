# EME web app, phase 2 (HB9Q): implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The HB9Q tab of the Android app (read only, 13 bands, Messages, QRV, Chat CQ, "Log out") and the HB9Q login in Settings, in the web app at `https://eme.on8ar.eu`, with each user logging in with their own HB9Q login.

**Architecture:** The Go server (`web/server`) logs in at logger.hb9q.ch for each web session, with a fresh cookie jar per login, and keeps that session in memory under an `HttpOnly` cookie. It passes the raw `logger_lst.cfm` answer to the browser, at most every 20 s per session and band. The browser keeps the login in `localStorage`, sends the password only to `POST /api/hb9q/login`, and parses the rows with a port of `ChatParser.kt` and `ChatCqParser.kt`.

**Tech Stack:** Node 24, Vite, Vitest, jsdom (`DOMParser`); Go 1.26, standard library only (`net/http/cookiejar`, `regexp`); nginx 1.14 on nolcluster.

**Spec:** `docs/superpowers/specs/2026-11-14-eme-web-design.md` (sections "Constraints", "Approaches" 1, "Calls made by Claude": server endpoints, password storage).

## Global Constraints

- HB9Q stays read only. The server sends logger.hb9q.ch exactly what `Hb9qClient.kt` sends: `GET /`, `POST /` (login form `swordfisch`, `txtLoginName`, `txtPwd`), `POST /rpc/logger/logger_lst.cfm?vBand=<id>&vSearch=&vSearchDt=0` with an empty body, `GET /?srv=logout`. Nothing else.
- Every HB9Q login starts with a fresh cookie jar (#23).
- Messages per web session and band: at most 1 HB9Q request every 20 s (`ChatStore.MESSAGES_REFRESH_MS`). A request within 20 s gets the cached answer.
- Failed logins: the backoff of `LoginBackoff.kt` (5 s, doubling, at most 300 s), kept per HB9Q login (case-insensitive) across the whole server.
- Web sessions live in memory only and expire after 30 minutes without a request. Nothing about HB9Q is written to disk.
- The password is never stored on the server, never logged, never in a URL or a response. It travels only in the JSON body of `POST /api/hb9q/login`.
- Session cookie: name `eme_hb9q`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/hb9q/`.
- User-Agent of the server: `EME-ON8AR-Web/<versionName>` (already in `newHandler`).
- Message text, names and callsigns come from other people: `textContent` only, never markup.
- Texts only from the 13 `strings.xml` files. A new text goes into all 13.
- Source of truth for behaviour is the Kotlin code at the commit the task starts from (`chat/*.kt`, `settings/SettingsScreen.kt`, `settings/SavedPassword.kt`). Ported tests keep the Kotlin test names and the exact expected values.
- This phase only adds to `web/` (plus 1 text in `strings.xml`, see task 5). Every pull request says it is web only, with the reason: the feature already exists in the Android app.
- One pull request per task, branched from `upstream/main`, in task order. Before each commit: `npm test` in `web/`, `go test ./...` in `web/server/`, and `./gradlew testDebugUnitTest` when `app/` changes.
- Git rules from `CLAUDE.md`: stage files by name, no force-push, never commit on `main`. No e-mail address in test data (`xxxx@test.com`), no real login or password in tests (use `ON0AAA` / `pw-a`, as `Hb9qClientTest.kt`).

## Review Focus

1. **The HB9Q session ends while the tab is open** (server restart, HB9Q session timeout). The browser logs in again once with the stored login and shows the messages, not an error. Test in task 4 (`sessionExpiredLogsInAgainOnce`).
2. **A wrong password stays stored.** Polling every 20 s must not make the server try HB9Q every 20 s: the backoff blocks retries without contacting HB9Q, also from a new session. The screen shows `login_failed`. Test in task 3 (`cooldownBlocksRetriesWithoutContactingHb9q`).
3. **The user changes the HB9Q login in Settings while a session exists.** The next fetch logs in as the new login (as `changingTheLoginWhileASessionIsActiveLogsInAgain`). Test in task 4.
4. **Several requests for the same session and band at once** (2 tabs, refresh button). Exactly 1 request goes to HB9Q. Test in task 3 (`parallelRequestsMakeOneUpstreamCall`).
5. **The page is hidden or the tab is left during a fetch.** No error is shown, and the attempt still counts for the 20 s (as `cancelledFetchIsNotStoredAsError`, `cancelledFetchStillCountsForTheWaitTime`). Test in task 4.

---

### Task 1: HB9Q parsers in JavaScript

**Files:**
- Create: `web/src/lib/hb9q.js`
- Test: `web/test/hb9q.test.js` (`// @vitest-environment jsdom`, for `DOMParser`)

**Interfaces:**
- Produces:
  - `HB9Q_BANDS: Array<{ name: string, id: number, label: string }>` in the order of `Hb9qBand` (`B50`/1/`50` ... `B76G`/13/`76032`).
  - `bandByName(name: string) -> band | undefined`.
  - `parseRows(html: string) -> Message[]`, Message = `{ id: number, time, fullTime, callsign, name, recipient: string|null, text, isCq: boolean, senderId: string|null }`.
  - `loggerRows(jsonText: string) -> string`: port of `Hb9qClient.loggerRows` plus the checks in `postJson`: not JSON throws `Error('HB9Q: unexpected answer')`, `status !== 'success'` throws `Error('HB9Q: data not available')`, no `logger` field throws `Error("HB9Q: antwoord zonder 'logger'-veld")` (verbatim, as the livecq Dutch text ruling of phase 1).
  - `recentParticipants(messages, myCallsign) -> Array<{ time, callsign, name }>` (port of `ChatStore.recentParticipants`).
  - `CHAT_CQ_WINDOW_MINUTES = 60`, `isCq(msg)`, `parseCq(msg, nowMs) -> ChatCq | null`, `recentCqs(messages, nowMs, minutes = 60) -> ChatCq[]`, `messageMillis(msg, nowMs) -> number | null`, `clean(text) -> string` (ports of `ChatCqParser`). ChatCq = `{ messageId, utcMillis, time, freq, period, callsign, name, mode, cfom, hz }`.

- [ ] **Step 1: Write the failing tests**

Port these Kotlin tests with their names and exact values, reading the samples from `app/src/test/resources/` (`hb9q_logger_rows.html`, `hb9q_rows_8nov.html`):
- `ChatParserTest`: `parsesAllRows` (63 rows, 12 CQ), `cqMessage`, `directedMessage`, `noEmailAddressesInParsedData`, `senderIdForReplies`, and the `parseRows` lines of `emptyOrGarbage`.
- `ChatCqParserTest`: every test except `whoListFromNewPage`.
- `Hb9qClientTest`: `aGenuinelyEmptyChatHasAnEmptyLoggerField`, `rowsArePassedThroughUnchanged`, `aMissingLoggerFieldIsTreatedAsAnErrorNotAnEmptyChat`, plus `notJsonIsAnUnexpectedAnswer` and `statusOtherThanSuccessIsAnError` for the 2 checks from `postJson`.
- `ChatStoreTest`: `recentParticipantsWithTime`.
- `bands`: 13 entries, ids and labels as `Hb9qBand`, `bandByName('B1296').id === 6`.
- `markupInTextStaysText`: a row whose text cell holds `<img src=x onerror=alert(1)>` gives that string as text, with no element.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web && npx vitest run test/hb9q.test.js`
Expected: FAIL, `hb9q.js` does not exist.

- [ ] **Step 3: Implement `web/src/lib/hb9q.js`**

`parseRows` wraps the input as `ChatParser` does (`<table><tbody>${html}</tbody></table>`) and parses it with `new DOMParser().parseFromString(..., 'text/html')`, which runs no scripts. `text()` of Jsoup becomes `textContent` with whitespace collapsed to 1 space and trimmed. The regular expressions of `ChatCqParser` are copied as they are (JavaScript supports the lookbehind in `HZ`); dates are computed in UTC as in Kotlin.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd web && npm test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/lib/hb9q.js web/test/hb9q.test.js
git commit -m "web: port the HB9Q chat parsers"
```

---

### Task 2: HB9Q connection and login backoff in the server

**Files:**
- Create: `web/server/hb9q_upstream.go`
- Test: `web/server/hb9q_upstream_test.go`, `web/server/fakehb9q_test.go` (port of `FakeHb9q.kt` on `httptest.Server`, with counters `loginPosts`, `loggerPosts`, `logouts` and the last User-Agent)

**Interfaces:**
- Produces:
  - `type loginBackoff struct`; `(b *loginBackoff) retryAfter(now time.Time) time.Duration` (0 means a login may be tried), `onSuccess()`, `onFailure(now time.Time)`. Constants 5 s, 300 s, max shift 6, as `LoginBackoff.kt`.
  - `var errLoginFailed`, `errSessionExpired` (sentinel errors).
  - `newHb9qConn(baseURL, userAgent string, timeout time.Duration) *hb9qConn`: own `cookiejar.Jar`, `CheckRedirect` returns `http.ErrUseLastResponse` (redirects are handled as in `Hb9qClient.request`).
  - `(c *hb9qConn) login(ctx context.Context, login, password string) error`: `GET /`, the value of `input[name=swordfisch]` (else error `HB9Q: login form not found`), `POST /` form-encoded with `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`; a 3xx after that POST is followed with a `GET` of `Location` resolved against the page URL; success when the page contains `srv=logout`, else `errLoginFailed`.
  - `(c *hb9qConn) messages(ctx context.Context, bandID int) ([]byte, error)`: the `logger_lst.cfm` POST; a 3xx or a body that does not start with `{` (after leading whitespace) gives `errSessionExpired`; HTTP 400 and up gives the error text `logger_lst: HTTP <code>` (as `shortName`). Body limited to `maxBodyBytes`.
  - `(c *hb9qConn) logout(ctx context.Context)`: `GET /?srv=logout`, errors ignored.

- [ ] **Step 1: Write the failing tests**

- Ports of `LoginBackoffTest` (all 5 tests, same names in Go style: `TestBackoffNoCooldownBeforeAnyFailure`, ...; `neverWaitsMoreThanFiveMinutes` expects exactly 300 s).
- `TestConnLogsInAndFetches`: login `ON0AAA`/`pw-a`, `messages(6)` returns `{"status":"success","logger":""}`, fake saw 1 login POST and band `vBand=6&vSearch=&vSearchDt=0`.
- `TestConnWrongPasswordIsLoginFailed`: `errors.Is(err, errLoginFailed)`.
- `TestConnFreshCookieJarPerConn`: 2 conns get 2 different fake sessions.
- `TestConnFollowsRedirectAfterLoginWithCookies`: the fake answers the login POST with 302 to `/`; login still succeeds.
- `TestConnSessionExpiredOnRedirect`: after the fake drops the session, `messages` returns `errSessionExpired`.
- `TestConnHTTPErrorText`: fake returns 500 for `logger_lst.cfm`; error text is `logger_lst: HTTP 500`.
- `TestConnFormEncoding`: a password `a&b c=d` arrives intact at the fake.
- `TestConnSendsUserAgent`: every request carries the given User-Agent.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web/server && go test ./... -run 'Backoff|Conn'`
Expected: FAIL, undefined names.

- [ ] **Step 3: Implement `web/server/hb9q_upstream.go`**

Find the token with a regular expression over `<input ...>` tags that checks `name` and reads `value` in any attribute order and quote style (no HTML library: the module has no dependencies). Decode bodies as UTF-8, as `Hb9qClient.kt`.

- [ ] **Step 4: Run all server tests**

Run: `cd web/server && go vet ./... && go test ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/server/hb9q_upstream.go web/server/hb9q_upstream_test.go web/server/fakehb9q_test.go
git commit -m "web/server: HB9Q login, rows and logout with the backoff of the app"
```

---

### Task 3: HB9Q endpoints and web sessions in the server

**Files:**
- Create: `web/server/hb9q_handlers.go`
- Modify: `web/server/main.go` (`config.hb9qURL` default `https://logger.hb9q.ch`; `newHandler` registers the HB9Q routes)
- Modify: `web/deploy/nginx-eme.on8ar.eu.conf` (a `location ^~ /api/hb9q/` with `proxy_read_timeout 60s` and `client_max_body_size 8k`; the 3 HB9Q requests of a login can take longer than the 20 s of `/api/`)
- Modify: `web/deploy/README.md` (the nginx change must be installed by hand before the first release with HB9Q, with `nginx -t` and a reload)
- Modify: `CONTRIBUTING.md` ("Rules for the external services": the web server logs in at HB9Q per web user with that user's own login, at most every 20 s per session and band, the same backoff, no password stored) and `CLAUDE.md` ("Never": the HB9Q line covers `web/server` too)
- Test: `web/server/hb9q_handlers_test.go`

**Interfaces:**
- Consumes: task 2 (`hb9qConn`, `loginBackoff`, `errLoginFailed`, `errSessionExpired`).
- Produces (HTTP, used by task 4):
  - `POST /api/hb9q/login`, `Content-Type: application/json` (else 415), body at most 4096 bytes `{"login":"…","password":"…"}` (empty field: 400). 200 `{"ok":true}` with `Set-Cookie: eme_hb9q=…`. 401 `{"error":"login_failed"}`. 429 `{"error":"cooldown","retryAfterMs":N}` (backoff) or `{"error":"busy","retryAfterMs":N}` (server-wide limit). 502 `{"error":"upstream","detail":"…"}`.
  - `GET /api/hb9q/messages?band=<id>`, id 1 to 13 as `Hb9qBand` (else 400). 200 `text/plain` with the raw `logger_lst.cfm` body and `X-Fetched-At` (unix ms). 401 `{"error":"no_session"}` or `{"error":"session_expired"}`. 502 `{"error":"upstream","detail":"…"}`.
  - `POST /api/hb9q/logout`: 204, HB9Q logout when a session exists, session removed, cookie cleared (`Max-Age=0`).
  - Wrong method: 405 with `Allow`. Every answer: `Cache-Control: no-store`; error bodies are JSON.

- [ ] **Step 1: Write the failing tests**

Against the fake of task 2, with a test clock:
- `TestLoginSetsSessionCookie`: attributes `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/hb9q/`; value of at least 32 random bytes, base64url.
- `TestLoginWrongPasswordIs401`.
- `TestLoginRequiresJSON`: form body gives 415 and no HB9Q request.
- `TestCooldownBlocksRetriesWithoutContactingHb9q`: after a failed login, a second login within 5 s (also without a cookie, and with the login in other case) gives 429 `cooldown`, and the fake's `loginPosts` stays 1; after 5 s a try goes through.
- `TestServerWideLoginLimit`: at most 10 HB9Q login attempts per minute for the whole server; the 11th gives 429 `busy` without contacting HB9Q.
- `TestMessagesWithoutSessionIs401`.
- `TestMessagesCachedWithin20s`: 2 requests 19 s apart make 1 `logger_lst` call and return the same body and `X-Fetched-At`; at 20 s a new call goes out.
- `TestGatePerBand`: band 6 and band 4 directly after each other make 2 calls.
- `TestParallelRequestsMakeOneUpstreamCall`: 20 parallel requests for 1 session and band, 1 call.
- `TestBadBandIs400`: `0`, `14`, `x`, missing.
- `TestSessionExpiredDropsTheSession`: fake drops the HB9Q session; answer 401 `session_expired`, the next request 401 `no_session`.
- `TestIdleSessionExpiresAfter30Minutes`.
- `TestLogoutCallsHb9qAndClearsCookie`.
- `TestPasswordNeverInLogOrAnswer`: capture `log` output; run a successful login, a failed login and an upstream error; the password string appears in no log line and no response header or body.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web/server && go test ./... -run 'Login|Messages|Gate|Parallel|BadBand|Session|Logout|Password'`
Expected: FAIL.

- [ ] **Step 3: Implement `web/server/hb9q_handlers.go`**

1 mutex per session serialises its HB9Q requests, so a parallel request waits and then gets the cached answer. Expired sessions are swept at the start of each HB9Q request (no goroutine, so the test clock decides). A login with an existing cookie replaces that session. Each handler runs under a context of 50 s. Log only upstream error texts, never a request body.

- [ ] **Step 4: Run all server tests**

Run: `cd web/server && go vet ./... && go test ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/server/hb9q_handlers.go web/server/hb9q_handlers_test.go web/server/main.go web/deploy/nginx-eme.on8ar.eu.conf web/deploy/README.md CONTRIBUTING.md CLAUDE.md
git commit -m "web/server: HB9Q endpoints with one HB9Q session per web session"
```

---

### Task 4: HB9Q store, API calls and stored login in the browser

**Files:**
- Create: `web/src/hb9q-store.js`, `web/src/credentials.js`
- Modify: `web/src/settings.js` (`chatBand`, default `'B1296'`, only names from `HB9Q_BANDS`)
- Test: `web/test/hb9q-store.test.js` (jsdom, `test/fetch-stub.js`), `web/test/credentials.test.js`, `web/test/settings.test.js`

**Interfaces:**
- Consumes: task 1 (`parseRows`, `loggerRows`), task 3 (HTTP API).
- Produces:
  - `credentials.js`: `loadCredentials() -> { login, password } | null` (null unless both are non-empty strings), `saveCredentials(login, password)`, `clearCredentials()`. Key `eme.hb9q`, every `localStorage` access in try/catch.
  - `hb9q-store.js`: `HB9Q_REFRESH_MS = 20_000`; `createHb9qStore({ now } = {})` returning `{ getState, subscribe(listener) -> unsubscribe, refreshIfOlderThan(maxAgeMs, band, creds) -> Promise, abort(), logout() -> Promise, logInAgain() }`; `errorFor(state, band)`; shared instance `hb9qStore`.
  - State (port of `ChatStore.State`): `{ band: string|null, messages, error: { key, detail } | null, loading, lastFetch, lastAttempt, fetchedAt, loggedOut }`.
  - Error mapping (as `ChatStore.refreshIfOlderThan`): `login_failed` gives `{ key: 'login_failed', detail: '' }`; `cooldown` gives `{ key: 'hb9q_error', detail: 'HB9Q: te veel mislukte logins, even wachten' }` (verbatim from `LoginOnCooldownException`); `busy` gives `{ key: 'hb9q_error', detail: 'HB9Q: too many logins on this server, try again in a minute' }` (web only); any other error gives `{ key: 'hb9q_error', detail: <message> }`.

- [ ] **Step 1: Write the failing tests**

- `credentials.test.js`: save, load, clear; `null` for a missing password; no throw when `localStorage` throws.
- `settings.test.js`: `chatBand` default `B1296`, unknown name falls back to `B1296`.
- `hb9q-store.test.js`:
  - `firstFetchLogsInThenReadsRows`: calls `POST /api/hb9q/login` with the JSON body, then `GET /api/hb9q/messages?band=6`; state has the parsed messages and `fetchedAt` from `X-Fetched-At`.
  - `secondFetchReusesTheSession`: no second login.
  - `sessionExpiredLogsInAgainOnce`: messages answers 401 `session_expired`, then 200; 1 extra login; no error in the state. A second 401 after that re-login gives an error, not a loop.
  - `changingTheLoginWhileASessionIsActiveLogsInAgain`: other `creds.login`, new login before the read.
  - `loginFailedIsShownAsLoginFailed`, `cooldownIsShownAsHb9qError`, `upstreamErrorKeepsTheMessages`.
  - Ports of `ChatStoreTest`: `cancelledFetchIsNotStoredAsError`, `cancelledFetchStillCountsForTheWaitTime`, `failedFetchIsStoredAsError`, `errorOnlyShownForTheBandItBelongsTo`.
  - `otherBandClearsTheList`, `withinMaxAgeNothingIsFetched`.
  - `logoutPostsLogoutAndStopsFetching`: state `loggedOut: true`; `refreshIfOlderThan` does nothing until `logInAgain()`.
  - `passwordOnlyInTheLoginBody`: across all calls, the password appears only in the body of `POST /api/hb9q/login`, never in a URL.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web && npx vitest run test/hb9q-store.test.js test/credentials.test.js test/settings.test.js`
Expected: FAIL.

- [ ] **Step 3: Implement**

The store keeps the login of the current server session (`sessionLogin`); it logs in first when that is empty or differs from `creds.login`, and once more after 401 `no_session` or `session_expired`. 1 request at a time: a call while one runs returns without a new request. `abort()` cancels the running request without an error and keeps `lastAttempt`. Requests use `credentials: 'same-origin'`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd web && npm test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/hb9q-store.js web/src/credentials.js web/src/settings.js web/test/hb9q-store.test.js web/test/credentials.test.js web/test/settings.test.js
git commit -m "web: HB9Q store with login per web session and the stored login"
```

---

### Task 5: HB9Q login in Settings

**Files:**
- Modify: `web/src/screens/settings.js`, `web/src/theme.css`
- Modify: the 13 `app/src/main/res/values*/strings.xml` (new text `login_stored_browser`, English: `Your login is stored in this browser on this device.`; translated in each language). `login_encrypted` is not true for a browser, so the web shows the new text instead. Android does not use it; the pull request says so.
- Test: `web/test/settings.test.js` (screen part, jsdom)

**Interfaces:**
- Consumes: task 4 (`loadCredentials`, `saveCredentials`, `clearCredentials`).
- Produces: a section below the station fields, port of `Hb9qLoginSection` / `Hb9qLoginFields`: title `hb9q_chat`, text `login_stored_browser`, field `hb9q_login` (upper case, trimmed), password field (`password`, or `password_saved` when a password is stored; never filled with the stored password; `show`/`hide` toggle), button `save_login` (enabled when the login is not empty and a password is typed or stored), button `clear` (only when a login is stored), message `login_saved` / `login_cleared`.

- [ ] **Step 1: Write the failing tests**

- `savesLoginAndPassword`, `emptyPasswordKeepsTheSavedOne` (as `SavedPasswordTest`), `passwordFieldNeverShowsTheSavedPassword`, `clearRemovesTheLogin`, `saveDisabledWithoutLogin`, `showTogglesTheInputType`.
- Android: `TranslationsTest` covers the new text in 13 languages.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web && npx vitest run test/settings.test.js`
Expected: FAIL.

- [ ] **Step 3: Implement**

Saving the login does not touch the station fields, and the station "Save" does not touch the login (as in the app, 2 separate buttons). The password input gets `autocomplete="current-password"` and the login `autocomplete="username"`, so Safari can offer its own password manager.

- [ ] **Step 4: Run all tests**

Run: `cd web && npm test` and `./gradlew testDebugUnitTest`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/screens/settings.js web/src/theme.css web/test/settings.test.js app/src/main/res/values/strings.xml app/src/main/res/values-*/strings.xml
git commit -m "web: HB9Q login in Settings"
```

(`git add` with the 12 `values-*` paths named one by one, as the staging rule asks.)

---

### Task 6: HB9Q tab

**Files:**
- Create: `web/src/screens/hb9q.js`
- Modify: `web/src/screens/index.js` (`chat: createHb9qScreen`), `web/src/app.js` (tab `{ id: 'chat', label: 'tab_chat' }` between Deg and N0UK, as the app; drop the phase 1 comment), `web/src/theme.css`, `README.md` (web section: HB9Q is in)
- Test: `web/test/hb9q-screen.test.js` (jsdom), `web/test/app.test.js`

**Interfaces:**
- Consumes: task 1 (`HB9Q_BANDS`, `bandByName`, `recentParticipants`, `recentCqs`), task 4 (`hb9qStore`, `errorFor`, `HB9Q_REFRESH_MS`, `loadCredentials`, `saveSettings({ chatBand })`). `ctx.hb9q` overrides the store and `ctx.credentials` the stored login, for tests.
- Produces: screen contract `{ mount(el, ctx), unmount() }`, port of `ChatScreen.kt` and `ChatCqScreen.kt`:
  - No login: text `need_login`, nothing fetched. Logged out: `logged_out` and button `log_in` (calls `logInAgain()`).
  - 13 band chips (`aria-pressed`); a click saves `chatBand`.
  - Sub-tabs `messages`, `participants` (with the count), `tab_chatcq`; spinner while loading, else a refresh button (`refreshIfOlderThan(5_000, …)`, as the app; the server still gives fresh rows at most every 20 s); button `log_out`.
  - Error card with `t(error.key, error.detail)`, only for the band it belongs to.
  - Messages newest first: time, callsign (class `mine` when it is the user's callsign), name, `→ recipient`; a message to the user gets the "to me" background; CQ text in the CQ colour.
  - QRV: `recent_in_chat` and the writers with time, callsign, name.
  - Chat CQ: `chatcq_title` (band label, count), header from `chatcq_header` split on ` - `, `chatcq_empty`, rows with `own echo` shown as `echo`.
  - Polling: `visibleInterval(() => refreshIfOlderThan(HB9Q_REFRESH_MS, band, creds), 5_000, abort)`; unmount stops it and aborts.
  - Colours as CSS custom properties with the values of `ChatScreen.kt` (`CQ_COLOR`, `TO_ME_BG`, error colours already in `theme.css`).

- [ ] **Step 1: Write the failing tests**

- `needLoginWithoutCredentials`: text shown, no fetch.
- `rendersMessagesOfTheStore`, `messageToMeIsMarked`, `cqTextHasTheCqClass`.
- `bandChipSwitchesAndSavesChatBand`.
- `logoutShowsLoggedOutAndLogInResumes`.
- `errorShownOnlyForItsBand`.
- `chatCqTableFromTheRows`: with `hb9q_rows_8nov.html` at a fixed `ctx.now`, F5AOL with `1296.088`.
- `markupInMessagesStaysText`: no `img` element in the screen.
- `pollsWhileMountedAndStopsAfterUnmount`.
- `app.test.js`: tab bar order Moon, Deg, HB9Q, N0UK, Live CQ.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd web && npx vitest run test/hb9q-screen.test.js test/app.test.js`
Expected: FAIL.

- [ ] **Step 3: Implement `web/src/screens/hb9q.js`**

Follow the structure of `web/src/screens/n0uk.js` (helpers `el`, `tagged`, `data-role` for tests). The Chat CQ table reuses the N0UK table styles where the columns match.

- [ ] **Step 4: Run all tests and the build**

Run: `cd web && npm test && npm run build`
Expected: all pass, build without warnings.

- [ ] **Step 5: Commit**

```bash
git add web/src/screens/hb9q.js web/src/screens/index.js web/src/app.js web/src/theme.css web/test/hb9q-screen.test.js web/test/app.test.js README.md
git commit -m "web: HB9Q tab"
```

---

### Task 7: Deploy and check on nolcluster (needs efite's confirmation per step)

**Files:** none in the repository.

- [ ] **Step 1:** After the first release whose zip contains HB9Q: install the nginx site of task 3 on nolcluster, `nginx -t`, reload. Server change: ask first.
- [ ] **Step 2:** The update timer installs the release; check `journalctl -u eme-web-update` and `/api/version`.
- [ ] **Step 3:** Check with `curl` that `/api/hb9q/messages?band=6` without a cookie gives 401 `no_session`, and that a login with a wrong password gives 401 and a second one 429.
- [ ] **Step 4:** efite logs in with their own HB9Q login on the iPhone and checks the 3 sub-pages and "Log out".
