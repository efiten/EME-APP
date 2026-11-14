package main

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{t: time.UnixMilli(1_700_000_000_000)} }
func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}
func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// upstream is a controllable fake of the external site.
type upstream struct {
	srv   *httptest.Server
	hits  atomic.Int32
	mu    sync.Mutex
	body  []byte
	ctype string
	code  int
	delay time.Duration
	lastR *http.Request

	lastBody string
}

func newUpstream(t *testing.T) *upstream {
	u := &upstream{body: []byte("v1"), code: 200}
	u.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u.hits.Add(1)
		rb, _ := io.ReadAll(r.Body)
		u.mu.Lock()
		u.lastBody = string(rb)
		body, ctype, code, delay := u.body, u.ctype, u.code, u.delay
		u.lastR = r
		u.mu.Unlock()
		if delay > 0 {
			time.Sleep(delay)
		}
		if ctype != "" {
			w.Header().Set("Content-Type", ctype)
		}
		w.WriteHeader(code)
		w.Write(body)
	}))
	t.Cleanup(u.srv.Close)
	return u
}

func (u *upstream) set(f func(u *upstream)) {
	u.mu.Lock()
	f(u)
	u.mu.Unlock()
}

func (u *upstream) request() *http.Request {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.lastR
}

func newTestCache(u *upstream, clk *fakeClock, timeout time.Duration) *cache {
	return newCache(source{
		name: "test", url: u.srv.URL, contentType: "text/plain; charset=utf-8",
		maxAge: 120 * time.Second,
	}, "EME-ON8AR-Web/1.2.3", timeout, clk.Now)
}

func TestWithinMaxAgeServesCacheWithoutFetch(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newTestCache(u, clk, time.Second)
	c.get()
	clk.Advance(119 * time.Second)
	r := c.get()
	if u.hits.Load() != 1 || string(r.body) != "v1" {
		t.Fatalf("hits=%d body=%q", u.hits.Load(), r.body)
	}
}

func TestAfterMaxAgeFetchesAgain(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newTestCache(u, clk, time.Second)
	c.get()
	u.set(func(u *upstream) { u.body = []byte("v2") })
	clk.Advance(120 * time.Second)
	r := c.get()
	if u.hits.Load() != 2 || string(r.body) != "v2" {
		t.Fatalf("hits=%d body=%q", u.hits.Load(), r.body)
	}
	if !r.fetchedAt.Equal(clk.Now()) {
		t.Fatalf("fetchedAt %v want %v", r.fetchedAt, clk.Now())
	}
}

func TestNeverTwiceWithinMaxAge(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 30 * time.Second},
		"ua", time.Second, clk.Now)
	c.get()
	clk.Advance(29 * time.Second)
	c.get()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d, want 1 within maxAge", u.hits.Load())
	}
	clk.Advance(1 * time.Second)
	c.get()
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d, want 2 after maxAge", u.hits.Load())
	}
}

func TestFailedAttemptAlsoCountsForMaxAge(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 30 * time.Second},
		"ua", time.Second, clk.Now)
	c.get()
	u.set(func(u *upstream) { u.code = 500 })
	clk.Advance(30 * time.Second)
	r := c.get() // fails
	if r.errText == "" {
		t.Fatal("expected error text")
	}
	clk.Advance(10 * time.Second)
	c.get()
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d, want 2 (no retry within maxAge after failure)", u.hits.Load())
	}
}

func TestUserAgentAndPlainGet(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	newTestCache(u, clk, time.Second).get()
	r := u.request()
	if got := r.Header.Get("User-Agent"); got != "EME-ON8AR-Web/1.2.3" {
		t.Fatalf("UA %q", got)
	}
	if r.Method != "GET" || r.ContentLength > 0 {
		t.Fatalf("method %s len %d", r.Method, r.ContentLength)
	}
}

func TestFailureKeepsLastGoodBody(t *testing.T) {
	cases := map[string]func(u *upstream){
		"500":     func(u *upstream) { u.code = 500 },
		"timeout": func(u *upstream) { u.delay = 600 * time.Millisecond },
	}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			u, clk := newUpstream(t), newFakeClock()
			c := newTestCache(u, clk, 200*time.Millisecond)
			first := c.get()
			u.set(f)
			clk.Advance(121 * time.Second)
			r := c.get()
			if r.status != 200 || string(r.body) != "v1" || r.errText == "" {
				t.Fatalf("status=%d body=%q err=%q", r.status, r.body, r.errText)
			}
			if !r.fetchedAt.Equal(first.fetchedAt) {
				t.Fatalf("fetchedAt must stay that of the good copy")
			}
		})
	}
}

func TestFirstFailureGives502(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	u.set(func(u *upstream) { u.code = 503 })
	r := newTestCache(u, clk, time.Second).get()
	if r.status != 502 || r.errText == "" || len(r.body) != 0 {
		t.Fatalf("%+v", r)
	}
}

func TestSuccessAfterFailureClearsError(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	u.set(func(u *upstream) { u.code = 500 })
	c := newTestCache(u, clk, time.Second)
	c.get()
	u.set(func(u *upstream) { u.code = 200 })
	clk.Advance(120 * time.Second)
	r := c.get()
	if r.status != 200 || r.errText != "" {
		t.Fatalf("%+v", r)
	}
}

func TestHundredParallelOneUpstreamHit(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	u.set(func(u *upstream) { u.delay = 100 * time.Millisecond })
	c := newTestCache(u, clk, 5*time.Second)
	var wg sync.WaitGroup
	res := make([]result, 100)
	for i := range res {
		wg.Add(1)
		go func() { defer wg.Done(); res[i] = c.get() }()
	}
	wg.Wait()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d", u.hits.Load())
	}
	for i, r := range res {
		if r.status != 200 || string(r.body) != "v1" {
			t.Fatalf("%d: %+v", i, r)
		}
	}
}

func TestHundredParallelAfterExpiry(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newTestCache(u, clk, 5*time.Second)
	c.get()
	u.set(func(u *upstream) { u.delay = 100 * time.Millisecond; u.body = []byte("v2") })
	clk.Advance(121 * time.Second)
	var wg sync.WaitGroup
	res := make([]result, 100)
	for i := range res {
		wg.Add(1)
		go func() { defer wg.Done(); res[i] = c.get() }()
	}
	wg.Wait()
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d, want 2 (initial + one refresh)", u.hits.Load())
	}
	for i, r := range res {
		if r.status != 200 || string(r.body) != "v2" {
			t.Fatalf("%d: %+v", i, r)
		}
	}
}

func TestLatin1Decoded(t *testing.T) {
	for _, ct := range []string{"text/html; charset=ISO-8859-1", `text/html; charset="iso-8859-1"`, "text/html; charset=latin1", "text/html;charset=Windows-1252"} {
		u, clk := newUpstream(t), newFakeClock()
		u.set(func(u *upstream) { u.ctype = ct; u.body = []byte{'c', 'a', 'f', 0xe9} })
		r := newTestCache(u, clk, time.Second).get()
		if string(r.body) != "café" {
			t.Fatalf("%s: %q", ct, r.body)
		}
	}
}

func TestWindows1252Table(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	u.set(func(u *upstream) {
		u.ctype = "text/html; charset=windows-1252"
		u.body = []byte{0x80, 0x93, 0x94, 0x96, 0x99, 0x81}
	})
	r := newTestCache(u, clk, time.Second).get()
	if string(r.body) != "€“”–™\u0081" {
		t.Fatalf("%q", r.body)
	}
}

func TestUTF8AndMissingCharsetPassThrough(t *testing.T) {
	for _, ct := range []string{"", "text/html", "text/html; charset=utf-8", "text/html; charset=UTF-8"} {
		u, clk := newUpstream(t), newFakeClock()
		u.set(func(u *upstream) { u.ctype = ct; u.body = []byte("café") })
		r := newTestCache(u, clk, time.Second).get()
		if string(r.body) != "café" {
			t.Fatalf("%q: %q", ct, r.body)
		}
	}
}

func TestProductionDefaults(t *testing.T) {
	if defaultTimeout != 15*time.Second {
		t.Fatalf("timeout %v", defaultTimeout)
	}
	cfg := defaultConfig("9.9")
	if cfg.timeout != 15*time.Second {
		t.Fatalf("cfg timeout %v", cfg.timeout)
	}
	s := cfg.sources()
	w, n, c := s["/api/livecq/w3sz"], s["/api/livecq/n6nu"], s["/api/n0uk"]
	if w.url != "https://w3sz.com/export_livecq3.php" ||
		n.url != "https://livecq.n6nu.org/api/spots?limit=200&offset=0&minutes=60&path=eme&lists=0" ||
		c.url != "https://www.chris.org/cgi-bin/jt65emeA" {
		t.Fatalf("urls %q %q %q", w.url, n.url, c.url)
	}
	if w.maxAge != 120*time.Second || n.maxAge != 120*time.Second || c.maxAge != 90*time.Second {
		t.Fatalf("intervals %+v %+v %+v", w, n, c)
	}
}

func TestFailingSiteRetriedOnlyAtMaxAgeCadence(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newTestCache(u, clk, time.Second) // maxAge 120 s
	good := c.get()
	u.set(func(u *upstream) { u.code = 500 })
	clk.Advance(120 * time.Second)
	c.get() // attempt 2, fails
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d", u.hits.Load())
	}
	// staggered browsers polling during the outage
	for _, d := range []time.Duration{31, 60, 90, 119} {
		clk.Now() // keep reading the clock
		clk.mu.Lock()
		clk.t = good.fetchedAt.Add(120*time.Second + d*time.Second)
		clk.mu.Unlock()
		r := c.get()
		if u.hits.Load() != 2 {
			t.Fatalf("+%ds: hits=%d, want no retry before maxAge", d, u.hits.Load())
		}
		if r.status != 200 || string(r.body) != "v1" || r.errText == "" || !r.fetchedAt.Equal(good.fetchedAt) {
			t.Fatalf("+%ds: %+v", d, r)
		}
	}
	clk.mu.Lock()
	clk.t = good.fetchedAt.Add(240 * time.Second)
	clk.mu.Unlock()
	c.get()
	if u.hits.Load() != 3 {
		t.Fatalf("hits=%d, want retry at +120 s after the failed attempt", u.hits.Load())
	}
}

func TestN0ukFailureRetriedAt90sNotEarlier(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	c := newCache(source{name: "n0uk", url: u.srv.URL, maxAge: 90 * time.Second},
		"ua", time.Second, clk.Now)
	c.get()
	u.set(func(u *upstream) { u.code = 500 })
	clk.Advance(90 * time.Second)
	c.get() // fails
	clk.Advance(89 * time.Second)
	c.get()
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d, want no retry at +89 s", u.hits.Load())
	}
	clk.Advance(1 * time.Second)
	c.get()
	if u.hits.Load() != 3 {
		t.Fatalf("hits=%d, want retry at +90 s", u.hits.Load())
	}
}

func TestGateHoldsAcrossRestart(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	path := filepath.Join(t.TempDir(), attemptsFile)
	src := source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}
	c1 := newCache(src, "ua", time.Second, clk.Now)
	c1.attachAttempts(loadAttempts(path))
	c1.get()
	clk.Advance(60 * time.Second)
	c2 := newCache(src, "ua", time.Second, clk.Now) // restart
	c2.attachAttempts(loadAttempts(path))
	c2.get()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d, want 1: restart must not reset the gate", u.hits.Load())
	}
	clk.Advance(60 * time.Second)
	c2.get()
	if u.hits.Load() != 2 {
		t.Fatalf("hits=%d, want 2 after maxAge", u.hits.Load())
	}
	if m, _ := filepath.Glob(path + ".tmp-*"); len(m) != 0 {
		t.Fatalf("temp files left: %v", m)
	}
}

func TestCorruptStateFileIgnored(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	path := filepath.Join(t.TempDir(), attemptsFile)
	os.WriteFile(path, []byte("{not json"), 0o644)
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}, "ua", time.Second, clk.Now)
	c.attachAttempts(loadAttempts(path))
	c.get()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d, want a first fetch", u.hits.Load())
	}
}

func TestFutureAttemptIgnored(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	path := filepath.Join(t.TempDir(), attemptsFile)
	future := clk.Now().Add(time.Hour).UnixMilli()
	os.WriteFile(path, []byte(fmt.Sprintf(`{"t":%d}`, future)), 0o644)
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}, "ua", time.Second, clk.Now)
	c.attachAttempts(loadAttempts(path))
	c.get()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d, want a first fetch", u.hits.Load())
	}
}

func TestHandlerStateDirSeedsGate(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	cfg := config{version: "1", timeout: time.Second, now: clk.Now, stateDir: t.TempDir(),
		w3szURL: w.srv.URL, n6nuURL: n.srv.URL, n0ukURL: c.srv.URL}
	do(newHandler(cfg), "GET", "/api/n0uk")
	clk.Advance(10 * time.Second)
	do(newHandler(cfg), "GET", "/api/n0uk") // restart
	if c.hits.Load() != 1 {
		t.Fatalf("hits=%d, want 1", c.hits.Load())
	}
}

func TestRestartInsideGateServesPersistedCopy(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	path := filepath.Join(t.TempDir(), attemptsFile)
	src := source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}
	c1 := newCache(src, "ua", time.Second, clk.Now)
	c1.attachAttempts(loadAttempts(path))
	first := c1.get()
	clk.Advance(60 * time.Second)
	c2 := newCache(src, "ua", time.Second, clk.Now) // restart
	c2.attachAttempts(loadAttempts(path))
	r := c2.get()
	if u.hits.Load() != 1 {
		t.Fatalf("hits=%d, want no request after the restart", u.hits.Load())
	}
	if r.status != 200 || string(r.body) != "v1" || !r.fetchedAt.Equal(first.fetchedAt) || r.errText != "" {
		t.Fatalf("%+v, want the copy of before the restart with its original fetchedAt %v", r, first.fetchedAt)
	}
}

func TestCorruptCopyIgnored(t *testing.T) {
	cases := map[string]func(dir string){
		"corrupt metadata": func(dir string) { os.WriteFile(filepath.Join(dir, "t.meta"), []byte("{no"), 0o644) },
		"missing body":     func(dir string) { os.Remove(filepath.Join(dir, "t.body")) },
		"size mismatch":    func(dir string) { os.WriteFile(filepath.Join(dir, "t.body"), []byte("short"), 0o644) },
		"oversized claim": func(dir string) {
			os.WriteFile(filepath.Join(dir, "t.meta"), []byte(fmt.Sprintf(`{"fetchedAt":1,"size":%d}`, maxBodyBytes+1)), 0o644)
		},
		"null metadata": func(dir string) { os.WriteFile(filepath.Join(dir, "t.meta"), []byte("null"), 0o644) },
	}
	for name, damage := range cases {
		t.Run(name, func(t *testing.T) {
			u, clk := newUpstream(t), newFakeClock()
			dir := t.TempDir()
			path := filepath.Join(dir, attemptsFile)
			src := source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}
			c1 := newCache(src, "ua", time.Second, clk.Now)
			c1.attachAttempts(loadAttempts(path))
			c1.get()
			damage(dir)
			c2 := newCache(src, "ua", time.Second, clk.Now)
			c2.attachAttempts(loadAttempts(path))
			if r := c2.get(); r.status != http.StatusBadGateway {
				t.Fatalf("status %d, want 502 (no copy, still inside the gate)", r.status)
			}
			if u.hits.Load() != 1 {
				t.Fatalf("hits=%d", u.hits.Load())
			}
		})
	}
}

func TestFutureCopyIgnored(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "t.body"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "t.meta"), []byte(fmt.Sprintf(`{"fetchedAt":%d,"size":1}`, clk.Now().Add(time.Hour).UnixMilli())), 0o644)
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}, "ua", time.Second, clk.Now)
	c.attachAttempts(loadAttempts(filepath.Join(dir, attemptsFile)))
	if r := c.get(); string(r.body) != "v1" || u.hits.Load() != 1 {
		t.Fatalf("%+v hits=%d, want a fresh fetch", r, u.hits.Load())
	}
}

func TestNullStateFileDoesNotPanic(t *testing.T) {
	u, clk := newUpstream(t), newFakeClock()
	path := filepath.Join(t.TempDir(), attemptsFile)
	os.WriteFile(path, []byte("null"), 0o644)
	c := newCache(source{name: "t", url: u.srv.URL, maxAge: 120 * time.Second}, "ua", time.Second, clk.Now)
	c.attachAttempts(loadAttempts(path))
	c.get() // writes the file
	if b, _ := os.ReadFile(path); !strings.Contains(string(b), `"t"`) {
		t.Fatalf("state file %q", b)
	}
}
