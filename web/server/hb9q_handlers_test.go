package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

type hb9qEnv struct {
	f   *fakeHb9q
	clk *fakeClock
	h   http.Handler
}

func newHb9qEnv(t *testing.T) *hb9qEnv {
	t.Helper()
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	t.Cleanup(f.close)
	clk := newFakeClock()
	h := newHandler(config{version: "1.2.3", timeout: 5 * time.Second, now: clk.Now, hb9qURL: f.baseURL()})
	return &hb9qEnv{f: f, clk: clk, h: h}
}

func (e *hb9qEnv) send(method, path, ctype, body string, cookie *http.Cookie) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	return rec
}

func (e *hb9qEnv) login(login, password string, cookie *http.Cookie) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"login": login, "password": password})
	return e.send("POST", "/api/hb9q/login", "application/json", string(body), cookie)
}

func (e *hb9qEnv) messages(band string, cookie *http.Cookie) *httptest.ResponseRecorder {
	path := "/api/hb9q/messages"
	if band != "" {
		path += "?band=" + band
	}
	return e.send("GET", path, "", "", cookie)
}

// loggedIn logs in as ON0AAA and returns the session cookie.
func (e *hb9qEnv) loggedIn(t *testing.T) *http.Cookie {
	t.Helper()
	rec := e.login("ON0AAA", "pw-a", nil)
	if rec.Code != 200 {
		t.Fatalf("login: %d %s", rec.Code, rec.Body.String())
	}
	return sessionCookie(t, rec)
}

func sessionCookie(t *testing.T, rec *httptest.ResponseRecorder) *http.Cookie {
	t.Helper()
	for _, c := range rec.Result().Cookies() {
		if c.Name == "eme_hb9q" {
			return c
		}
	}
	t.Fatalf("no eme_hb9q cookie in %v", rec.Header())
	return nil
}

func jsonErr(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("error Content-Type %q", ct)
	}
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("body %q: %v", rec.Body.String(), err)
	}
	return m
}

func expectErr(t *testing.T, rec *httptest.ResponseRecorder, code int, errName string) map[string]any {
	t.Helper()
	if rec.Code != code {
		t.Fatalf("status %d, want %d: %s", rec.Code, code, rec.Body.String())
	}
	m := jsonErr(t, rec)
	if m["error"] != errName {
		t.Fatalf("error %v, want %q", m["error"], errName)
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing Cache-Control: no-store")
	}
	return m
}

func TestLoginSetsSessionCookie(t *testing.T) {
	e := newHb9qEnv(t)
	rec := e.login("ON0AAA", "pw-a", nil)
	if rec.Code != 200 || strings.TrimSpace(rec.Body.String()) != `{"ok":true}` {
		t.Fatalf("%d %q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing Cache-Control")
	}
	c := sessionCookie(t, rec)
	if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode || c.Path != "/api/hb9q/" {
		t.Fatalf("cookie attributes: %+v", c)
	}
	raw, err := base64.RawURLEncoding.DecodeString(c.Value)
	if err != nil || len(raw) < 32 {
		t.Fatalf("cookie value %q: %d bytes, %v", c.Value, len(raw), err)
	}
	if again := sessionCookie(t, e.login("ON0AAA", "pw-a", nil)); again.Value == c.Value {
		t.Fatal("two logins gave the same session id")
	}
}

func TestLoginWrongPasswordIs401(t *testing.T) {
	e := newHb9qEnv(t)
	rec := e.login("ON0AAA", "pw-b", nil)
	expectErr(t, rec, 401, "login_failed")
	if len(rec.Result().Cookies()) != 0 {
		t.Fatal("cookie set on a failed login")
	}
}

func TestLoginRequiresJSON(t *testing.T) {
	e := newHb9qEnv(t)
	rec := e.send("POST", "/api/hb9q/login", "application/x-www-form-urlencoded", "login=ON0AAA&password=pw-a", nil)
	expectErr(t, rec, 415, "unsupported_media_type")
	if n := e.f.sessionCount(); n != 0 {
		t.Fatalf("HB9Q contacted: %d sessions", n)
	}
}

func TestLoginEmptyFieldOrBadBodyIs400(t *testing.T) {
	e := newHb9qEnv(t)
	for _, body := range []string{`{"login":"ON0AAA","password":""}`, `{"login":"","password":"pw-a"}`, `{`,
		`{"login":"ON0AAA","password":"` + strings.Repeat("x", 5000) + `"}`} {
		rec := e.send("POST", "/api/hb9q/login", "application/json", body, nil)
		expectErr(t, rec, 400, "bad_request")
	}
	if n := e.f.sessionCount(); n != 0 {
		t.Fatalf("HB9Q contacted: %d sessions", n)
	}
}

func TestWrongMethodIs405(t *testing.T) {
	e := newHb9qEnv(t)
	cases := []struct{ method, path, allow string }{
		{"GET", "/api/hb9q/login", "POST"},
		{"POST", "/api/hb9q/messages?band=6", "GET"},
		{"GET", "/api/hb9q/logout", "POST"},
	}
	for _, tc := range cases {
		rec := e.send(tc.method, tc.path, "", "", nil)
		expectErr(t, rec, 405, "method_not_allowed")
		if rec.Header().Get("Allow") != tc.allow {
			t.Fatalf("%s %s: Allow %q", tc.method, tc.path, rec.Header().Get("Allow"))
		}
	}
}

func TestCooldownBlocksRetriesWithoutContactingHb9q(t *testing.T) {
	e := newHb9qEnv(t)
	expectErr(t, e.login("ON0AAA", "pw-b", nil), 401, "login_failed")
	e.clk.Advance(4 * time.Second)
	for _, login := range []string{"ON0AAA", "on0aaa"} {
		m := expectErr(t, e.login(login, "pw-a", nil), 429, "cooldown")
		if ms, _ := m["retryAfterMs"].(float64); ms != 1000 {
			t.Fatalf("retryAfterMs %v, want 1000", m["retryAfterMs"])
		}
	}
	if posts, _, _ := e.f.counts(); posts != 1 {
		t.Fatalf("loginPosts = %d, want 1", posts)
	}
	e.clk.Advance(time.Second)
	if rec := e.login("ON0AAA", "pw-a", nil); rec.Code != 200 {
		t.Fatalf("after cooldown: %d %s", rec.Code, rec.Body.String())
	}
	if posts, _, _ := e.f.counts(); posts != 2 {
		t.Fatalf("loginPosts = %d, want 2", posts)
	}
}

func TestUpstreamLoginErrorIs502AndStartsCooldown(t *testing.T) {
	f := newFakeHb9q(nil)
	base := f.baseURL()
	f.close()
	clk := newFakeClock()
	h := newHandler(config{version: "1", timeout: 2 * time.Second, now: clk.Now, hb9qURL: base})
	e := &hb9qEnv{clk: clk, h: h}
	m := expectErr(t, e.login("ON0AAA", "pw-a", nil), 502, "upstream")
	if m["detail"] != "upstream unreachable" {
		t.Fatalf("detail %v", m["detail"])
	}
	expectErr(t, e.login("ON0AAA", "pw-a", nil), 429, "cooldown")
}

func TestServerWideLoginLimit(t *testing.T) {
	e := newHb9qEnv(t)
	for i := 0; i < 10; i++ {
		if rec := e.login("ON0AAA", "pw-a", nil); rec.Code != 200 {
			t.Fatalf("login %d: %d", i+1, rec.Code)
		}
		e.clk.Advance(time.Second)
	}
	m := expectErr(t, e.login("ON0AAA", "pw-a", nil), 429, "busy")
	if ms, _ := m["retryAfterMs"].(float64); ms != 50000 {
		t.Fatalf("retryAfterMs %v, want 50000", m["retryAfterMs"])
	}
	if posts, _, _ := e.f.counts(); posts != 10 {
		t.Fatalf("loginPosts = %d, want 10", posts)
	}
	e.clk.Advance(50 * time.Second)
	if rec := e.login("ON0AAA", "pw-a", nil); rec.Code != 200 {
		t.Fatalf("after a minute: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMessagesWithoutSessionIs401(t *testing.T) {
	e := newHb9qEnv(t)
	expectErr(t, e.messages("6", nil), 401, "no_session")
	expectErr(t, e.messages("6", &http.Cookie{Name: "eme_hb9q", Value: "unknown"}), 401, "no_session")
	if _, logger, _ := e.f.counts(); logger != 0 {
		t.Fatalf("loggerPosts = %d", logger)
	}
}

func TestMessagesCachedWithin20s(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	first := e.messages("6", c)
	if first.Code != 200 || first.Body.String() != `{"status":"success","logger":""}` {
		t.Fatalf("%d %q", first.Code, first.Body.String())
	}
	if ct := first.Header().Get("Content-Type"); ct != "text/plain; charset=utf-8" {
		t.Fatalf("Content-Type %q", ct)
	}
	if first.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing Cache-Control")
	}
	at := first.Header().Get("X-Fetched-At")
	if at != strconv.FormatInt(e.clk.Now().UnixMilli(), 10) {
		t.Fatalf("X-Fetched-At %q", at)
	}
	e.clk.Advance(19 * time.Second)
	second := e.messages("6", c)
	if second.Body.String() != first.Body.String() || second.Header().Get("X-Fetched-At") != at {
		t.Fatalf("second answer differs: %q %q", second.Body.String(), second.Header().Get("X-Fetched-At"))
	}
	if _, logger, _ := e.f.counts(); logger != 1 {
		t.Fatalf("loggerPosts = %d, want 1", logger)
	}
	e.clk.Advance(time.Second)
	third := e.messages("6", c)
	if third.Header().Get("X-Fetched-At") == at {
		t.Fatal("X-Fetched-At not renewed after 20 s")
	}
	if _, logger, _ := e.f.counts(); logger != 2 {
		t.Fatalf("loggerPosts = %d, want 2", logger)
	}
}

func TestGatePerBand(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	e.messages("6", c)
	e.messages("4", c)
	if _, logger, _ := e.f.counts(); logger != 2 {
		t.Fatalf("loggerPosts = %d, want 2", logger)
	}
}

func TestUpstreamErrorIsGatedToo(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	e.f.setLoggerStatus(500)
	m := expectErr(t, e.messages("6", c), 502, "upstream")
	if m["detail"] != "logger_lst: HTTP 500" {
		t.Fatalf("detail %v", m["detail"])
	}
	e.clk.Advance(10 * time.Second)
	expectErr(t, e.messages("6", c), 502, "upstream")
	if _, logger, _ := e.f.counts(); logger != 1 {
		t.Fatalf("loggerPosts = %d, want 1", logger)
	}
}

func TestParallelRequestsMakeOneUpstreamCall(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	var wg sync.WaitGroup
	codes := make([]int, 20)
	for i := range codes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i] = e.messages("6", c).Code
		}()
	}
	wg.Wait()
	for i, code := range codes {
		if code != 200 {
			t.Fatalf("request %d: %d", i, code)
		}
	}
	if _, logger, _ := e.f.counts(); logger != 1 {
		t.Fatalf("loggerPosts = %d, want 1", logger)
	}
}

func TestBadBandIs400(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	for _, band := range []string{"0", "14", "x", ""} {
		expectErr(t, e.messages(band, c), 400, "bad_band")
	}
	for _, band := range []string{"1", "13"} {
		if rec := e.messages(band, c); rec.Code != 200 {
			t.Fatalf("band %s: %d", band, rec.Code)
		}
	}
}

func TestSessionExpiredDropsTheSession(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	e.f.dropSessions()
	expectErr(t, e.messages("6", c), 401, "session_expired")
	expectErr(t, e.messages("4", c), 401, "no_session")
}

func TestIdleSessionExpiresAfter30Minutes(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	e.clk.Advance(29 * time.Minute)
	if rec := e.messages("6", c); rec.Code != 200 {
		t.Fatalf("after 29 min: %d", rec.Code)
	}
	e.clk.Advance(29 * time.Minute)
	if rec := e.messages("6", c); rec.Code != 200 {
		t.Fatalf("29 min after the last request: %d", rec.Code)
	}
	e.clk.Advance(30 * time.Minute)
	expectErr(t, e.messages("6", c), 401, "no_session")
}

func TestLoginWithCookieReplacesSession(t *testing.T) {
	e := newHb9qEnv(t)
	old := e.loggedIn(t)
	rec := e.login("ON0AAA", "pw-a", old)
	if rec.Code != 200 {
		t.Fatalf("%d", rec.Code)
	}
	expectErr(t, e.messages("6", old), 401, "no_session")
	if r := e.messages("6", sessionCookie(t, rec)); r.Code != 200 {
		t.Fatalf("new session: %d", r.Code)
	}
}

func TestLogoutCallsHb9qAndClearsCookie(t *testing.T) {
	e := newHb9qEnv(t)
	c := e.loggedIn(t)
	rec := e.send("POST", "/api/hb9q/logout", "", "", c)
	if rec.Code != 204 {
		t.Fatalf("logout: %d", rec.Code)
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing Cache-Control")
	}
	cleared := sessionCookie(t, rec)
	if cleared.MaxAge >= 0 || !strings.Contains(rec.Header().Get("Set-Cookie"), "Max-Age=0") || cleared.Path != "/api/hb9q/" {
		t.Fatalf("cookie not cleared: %q", rec.Header().Get("Set-Cookie"))
	}
	if _, _, logouts := e.f.counts(); logouts != 1 {
		t.Fatalf("logouts = %d, want 1", logouts)
	}
	expectErr(t, e.messages("6", c), 401, "no_session")

	if rec := e.send("POST", "/api/hb9q/logout", "", "", nil); rec.Code != 204 {
		t.Fatalf("logout without session: %d", rec.Code)
	}
	if _, _, logouts := e.f.counts(); logouts != 1 {
		t.Fatalf("logouts = %d, want still 1", logouts)
	}
}

func TestPasswordNeverInLogOrAnswer(t *testing.T) {
	var buf bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(prev)
	e := newHb9qEnv(t)
	var recs []*httptest.ResponseRecorder
	ok := e.login("ON0AAA", "pw-a", nil)
	recs = append(recs, ok)
	c := sessionCookie(t, ok)
	e.f.setLoggerStatus(500)
	recs = append(recs, e.messages("6", c))
	e.clk.Advance(time.Minute)
	recs = append(recs, e.login("ON0AAA", "pw-b", nil))
	for _, pw := range []string{"pw-a", "pw-b"} {
		if strings.Contains(buf.String(), pw) {
			t.Fatalf("password in log: %q", buf.String())
		}
		for i, rec := range recs {
			if strings.Contains(rec.Body.String(), pw) {
				t.Fatalf("answer %d body has the password", i)
			}
			for k, vs := range rec.Header() {
				for _, v := range vs {
					if strings.Contains(v, pw) {
						t.Fatalf("answer %d header %s has the password", i, k)
					}
				}
			}
		}
	}
	if !strings.Contains(buf.String(), "logger_lst: HTTP 500") {
		t.Fatalf("upstream error not logged: %q", buf.String())
	}
}
