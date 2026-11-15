package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestBackoffNoCooldownBeforeAnyFailure(t *testing.T) {
	var b loginBackoff
	if got := b.retryAfter(time.Unix(1, 0)); got != 0 {
		t.Fatalf("retryAfter = %v, want 0", got)
	}
}

func TestBackoffBlocksRetriesForAWhileAfterAFailure(t *testing.T) {
	var b loginBackoff
	now := time.Unix(1, 0)
	b.onFailure(now)
	if got := b.retryAfter(now); got <= 0 {
		t.Fatalf("retryAfter = %v, want > 0", got)
	}
}

func TestBackoffCooldownExpiresAndBacksOffFurther(t *testing.T) {
	var b loginBackoff
	now := time.Unix(0, 0)
	b.onFailure(now)
	first := b.retryAfter(now)
	if first != 5*time.Second {
		t.Fatalf("first wait = %v, want 5s", first)
	}
	if got := b.retryAfter(now.Add(first - time.Millisecond)); got <= 0 {
		t.Fatalf("just before the end: %v, want > 0", got)
	}
	later := now.Add(first + time.Millisecond)
	if got := b.retryAfter(later); got != 0 {
		t.Fatalf("after the wait: %v, want 0", got)
	}
	b.onFailure(later)
	if second := b.retryAfter(later); second < first {
		t.Fatalf("second wait %v < first %v", second, first)
	}
}

func TestBackoffSuccessReenablesImmediateRetries(t *testing.T) {
	var b loginBackoff
	now := time.Unix(0, 0)
	b.onFailure(now)
	if b.retryAfter(now) <= 0 {
		t.Fatal("expected a cooldown")
	}
	b.onSuccess()
	if got := b.retryAfter(now); got != 0 {
		t.Fatalf("retryAfter = %v, want 0", got)
	}
}

func TestBackoffNeverWaitsMoreThanFiveMinutes(t *testing.T) {
	var b loginBackoff
	now := time.Unix(0, 0)
	for i := 0; i < 20; i++ {
		b.onFailure(now)
	}
	if got := b.retryAfter(now); got != 300*time.Second {
		t.Fatalf("retryAfter = %v, want 300s", got)
	}
}

const testUA = "EME-ON5GS-Web/test"

func newTestConn(f *fakeHb9q) *hb9qConn {
	return newHb9qConn(f.baseURL(), testUA, 5*time.Second)
}

func TestConnLogsInAndFetches(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	c := newTestConn(f)
	ctx := context.Background()
	if err := c.login(ctx, "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
	body, err := c.messages(ctx, 6)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"status":"success","logger":""}` {
		t.Fatalf("body = %q", body)
	}
	if f.loginPosts != 1 {
		t.Fatalf("loginPosts = %d, want 1", f.loginPosts)
	}
	if f.lastLoggerQuery != "vBand=6&vSearch=&vSearchDt=0" {
		t.Fatalf("query = %q", f.lastLoggerQuery)
	}
}

func TestConnWrongPasswordIsLoginFailed(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	err := newTestConn(f).login(context.Background(), "ON0AAA", "pw-b")
	if !errors.Is(err, errLoginFailed) {
		t.Fatalf("err = %v, want errLoginFailed", err)
	}
}

func TestConnFreshCookieJarPerConn(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a", "ON0BBB": "pw-b"})
	defer f.close()
	ctx := context.Background()
	if err := newTestConn(f).login(ctx, "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
	if err := newTestConn(f).login(ctx, "ON0BBB", "pw-b"); err != nil {
		t.Fatal(err)
	}
	if n := f.sessionCount(); n != 2 {
		t.Fatalf("sessions = %d, want 2", n)
	}
}

func TestConnFollowsRedirectAfterLoginWithCookies(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	f.redirectAfterLogin = true
	if err := newTestConn(f).login(context.Background(), "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
}

func TestConnSessionExpiredOnRedirect(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	c := newTestConn(f)
	ctx := context.Background()
	if err := c.login(ctx, "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
	f.dropSessions()
	if _, err := c.messages(ctx, 6); !errors.Is(err, errSessionExpired) {
		t.Fatalf("err = %v, want errSessionExpired", err)
	}
}

func TestConnSessionExpiredOnNonJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("  <html>login</html>"))
	}))
	defer srv.Close()
	c := newHb9qConn(srv.URL, testUA, 5*time.Second)
	if _, err := c.messages(context.Background(), 6); !errors.Is(err, errSessionExpired) {
		t.Fatalf("err = %v, want errSessionExpired", err)
	}
}

func TestConnHTTPErrorText(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	c := newTestConn(f)
	ctx := context.Background()
	if err := c.login(ctx, "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
	f.loggerStatus = 500
	_, err := c.messages(ctx, 6)
	if err == nil || err.Error() != "logger_lst: HTTP 500" {
		t.Fatalf("err = %v", err)
	}
}

func TestConnFormEncoding(t *testing.T) {
	const pw = "a&b c=d"
	f := newFakeHb9q(map[string]string{"ON0AAA": pw})
	defer f.close()
	if err := newTestConn(f).login(context.Background(), "ON0AAA", pw); err != nil {
		t.Fatal(err)
	}
	if got := f.lastLoginForm.Get("txtPwd"); got != pw {
		t.Fatalf("password at the fake = %q", got)
	}
}

func TestConnSendsUserAgent(t *testing.T) {
	f := newFakeHb9q(map[string]string{"ON0AAA": "pw-a"})
	defer f.close()
	c := newTestConn(f)
	ctx := context.Background()
	check := func(step string) {
		t.Helper()
		if f.lastUserAgent != testUA {
			t.Fatalf("%s: User-Agent = %q", step, f.lastUserAgent)
		}
	}
	if err := c.login(ctx, "ON0AAA", "pw-a"); err != nil {
		t.Fatal(err)
	}
	check("login")
	if _, err := c.messages(ctx, 6); err != nil {
		t.Fatal(err)
	}
	check("messages")
	c.logout(ctx)
	check("logout")
	if f.logouts != 1 {
		t.Fatalf("logouts = %d", f.logouts)
	}
}

func TestConnLoginFormNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("<html>nothing</html>"))
	}))
	defer srv.Close()
	err := newHb9qConn(srv.URL, testUA, 5*time.Second).login(context.Background(), "ON0AAA", "pw-a")
	if err == nil || err.Error() != "HB9Q: login form not found" {
		t.Fatalf("err = %v", err)
	}
}
