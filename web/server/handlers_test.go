package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func testHandler(w, n, c *upstream, clk *fakeClock) http.Handler {
	return newHandler(config{
		version: "1.2.3", timeout: time.Second, now: clk.Now,
		w3szURL: w.srv.URL, n6nuURL: n.srv.URL, n0ukURL: c.srv.URL,
	})
}

func do(h http.Handler, method, path string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(method, path, nil))
	return rec
}

func TestEndpointsServeBodiesAndHeaders(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	w.set(func(u *upstream) { u.body = []byte("csv") })
	n.set(func(u *upstream) { u.body = []byte(`{"a":1}`) })
	c.set(func(u *upstream) { u.ctype = "text/html; charset=iso-8859-1"; u.body = []byte{'<', 0xe9} })
	h := testHandler(w, n, c, clk)
	cases := []struct{ path, body, ctype string }{
		{"/api/livecq/w3sz", "csv", "text/plain; charset=utf-8"},
		{"/api/livecq/n6nu", `{"a":1}`, "application/json; charset=utf-8"},
		{"/api/n0uk", "<é", "text/html; charset=utf-8"},
	}
	for _, tc := range cases {
		rec := do(h, "GET", tc.path)
		if rec.Code != 200 || rec.Body.String() != tc.body {
			t.Fatalf("%s: %d %q", tc.path, rec.Code, rec.Body.String())
		}
		if got := rec.Header().Get("Content-Type"); got != tc.ctype {
			t.Fatalf("%s ctype %q", tc.path, got)
		}
		if rec.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("%s cache-control", tc.path)
		}
		if rec.Header().Get("X-Fetched-At") != strconv.FormatInt(clk.Now().UnixMilli(), 10) {
			t.Fatalf("%s fetched-at %q", tc.path, rec.Header().Get("X-Fetched-At"))
		}
		if rec.Header().Get("X-Upstream-Error") != "" {
			t.Fatalf("%s unexpected error header", tc.path)
		}
	}
}

func TestUpstreamErrorHeader(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	h := testHandler(w, n, c, clk)
	do(h, "GET", "/api/livecq/w3sz")
	w.set(func(u *upstream) { u.code = 500 })
	clk.Advance(121 * time.Second)
	rec := do(h, "GET", "/api/livecq/w3sz")
	if rec.Code != 200 || rec.Body.String() != "v1" || rec.Header().Get("X-Upstream-Error") == "" {
		t.Fatalf("%d %q %v", rec.Code, rec.Body.String(), rec.Header())
	}
	c.set(func(u *upstream) { u.code = 500 })
	rec = do(h, "GET", "/api/n0uk")
	if rec.Code != 502 || rec.Header().Get("X-Upstream-Error") == "" {
		t.Fatalf("%d %v", rec.Code, rec.Header())
	}
	if strings.Contains(rec.Header().Get("X-Upstream-Error"), "127.0.0.1") {
		t.Fatal("error header leaks upstream address")
	}
}

func TestVersion(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	rec := do(testHandler(w, n, c, clk), "GET", "/api/version")
	if rec.Code != 200 || rec.Body.String() != `{"version":"1.2.3"}` {
		t.Fatalf("%d %q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Content-Type") != "application/json; charset=utf-8" || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("%v", rec.Header())
	}
	if w.hits.Load()+n.hits.Load()+c.hits.Load() != 0 {
		t.Fatal("version must not hit upstream")
	}
}

func TestVersionIsJSONEscaped(t *testing.T) {
	h := newHandler(config{version: `a"b`, timeout: time.Second, now: time.Now})
	if got := do(h, "GET", "/api/version").Body.String(); got != `{"version":"a\"b"}` {
		t.Fatal(got)
	}
}

func TestMethodsAndPaths(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	h := testHandler(w, n, c, clk)
	for _, m := range []string{"POST", "PUT", "DELETE", "PATCH"} {
		for _, p := range []string{"/api/n0uk", "/api/livecq/w3sz", "/api/version"} {
			rec := do(h, m, p)
			if rec.Code != 405 || rec.Header().Get("Allow") != "GET, HEAD" {
				t.Fatalf("%s %s: %d", m, p, rec.Code)
			}
		}
	}
	for _, p := range []string{"/", "/api/", "/api/nope", "/api/n0uk/x", "/api/livecq"} {
		if rec := do(h, "GET", p); rec.Code != 404 {
			t.Fatalf("%s: %d", p, rec.Code)
		}
	}
	if w.hits.Load()+n.hits.Load()+c.hits.Load() != 0 {
		t.Fatal("rejected requests must not hit upstream")
	}
}

func TestHeadHasNoBody(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	srv := httptest.NewServer(testHandler(w, n, c, clk))
	defer srv.Close()
	resp, err := http.Head(srv.URL + "/api/livecq/w3sz")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || len(b) != 0 || resp.Header.Get("X-Fetched-At") == "" {
		t.Fatalf("%d %q %v", resp.StatusCode, b, resp.Header)
	}
}

func TestHandlerHundredParallelOneHit(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	w.set(func(u *upstream) { u.delay = 100 * time.Millisecond })
	h := testHandler(w, n, c, clk)
	done := make(chan *httptest.ResponseRecorder, 100)
	for i := 0; i < 100; i++ {
		go func() { done <- do(h, "GET", "/api/livecq/w3sz") }()
	}
	for i := 0; i < 100; i++ {
		if rec := <-done; rec.Code != 200 || rec.Body.String() != "v1" {
			t.Fatalf("%d %q", rec.Code, rec.Body.String())
		}
	}
	if w.hits.Load() != 1 {
		t.Fatalf("hits=%d", w.hits.Load())
	}
}

func (u *upstream) lastBodyText() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.lastBody
}

func TestRequestsMatchTheApp(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	n.set(func(u *upstream) { u.body = []byte(`{"spots":[]}`) })
	h := testHandler(w, n, c, clk)
	for _, p := range []string{"/api/livecq/w3sz", "/api/livecq/n6nu", "/api/n0uk"} {
		if rec := do(h, "GET", p); rec.Code != 200 {
			t.Fatalf("%s: %d", p, rec.Code)
		}
	}
	r := w.request()
	if r.Method != "POST" || r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" || w.lastBodyText() != "time_period=1d" {
		t.Fatalf("w3sz: %s %q %q", r.Method, r.Header.Get("Content-Type"), w.lastBodyText())
	}
	if r.Header.Get("User-Agent") != "EME-ON8AR-Web/1.2.3" {
		t.Fatalf("w3sz UA %q", r.Header.Get("User-Agent"))
	}
	r = n.request()
	if r.Method != "GET" || r.Header.Get("Accept") != "application/json" || n.lastBodyText() != "" {
		t.Fatalf("n6nu: %s %q %q", r.Method, r.Header.Get("Accept"), n.lastBodyText())
	}
	r = c.request()
	if r.Method != "GET" || c.lastBodyText() != "" {
		t.Fatalf("n0uk: %s %q", r.Method, c.lastBodyText())
	}
}

func TestContentChecks(t *testing.T) {
	cases := []struct{ path, body, want string }{
		{"/api/livecq/w3sz", "  \n<html>login</html>", "W3SZ: no CSV data"},
		{"/api/livecq/n6nu", "<html>", "N6NU: no JSON data"},
		{"/api/livecq/n6nu", "[1,2]", "N6NU: no JSON data"},
	}
	for _, tc := range cases {
		w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
		w.set(func(u *upstream) { u.body = []byte(tc.body) })
		n.set(func(u *upstream) { u.body = []byte(tc.body) })
		rec := do(testHandler(w, n, c, clk), "GET", tc.path)
		if rec.Code != 502 || rec.Header().Get("X-Upstream-Error") != tc.want {
			t.Fatalf("%s: %d %q", tc.path, rec.Code, rec.Header().Get("X-Upstream-Error"))
		}
	}
}

func TestContentCheckFailureKeepsLastGood(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	h := testHandler(w, n, c, clk)
	do(h, "GET", "/api/livecq/w3sz")
	w.set(func(u *upstream) { u.body = []byte("<html>") })
	clk.Advance(121 * time.Second)
	rec := do(h, "GET", "/api/livecq/w3sz")
	if rec.Code != 200 || rec.Body.String() != "v1" || rec.Header().Get("X-Upstream-Error") != "W3SZ: no CSV data" {
		t.Fatalf("%d %q %q", rec.Code, rec.Body.String(), rec.Header().Get("X-Upstream-Error"))
	}
}

func TestOnly200Accepted(t *testing.T) {
	w, n, c, clk := newUpstream(t), newUpstream(t), newUpstream(t), newFakeClock()
	c.set(func(u *upstream) { u.code = 204 })
	w.set(func(u *upstream) { u.code = 500 })
	h := testHandler(w, n, c, clk)
	if rec := do(h, "GET", "/api/n0uk"); rec.Code != 502 || rec.Header().Get("X-Upstream-Error") != "N0UK HTTP 204" {
		t.Fatalf("%d %q", rec.Code, rec.Header().Get("X-Upstream-Error"))
	}
	if rec := do(h, "GET", "/api/livecq/w3sz"); rec.Header().Get("X-Upstream-Error") != "W3SZ HTTP 500" {
		t.Fatalf("%q", rec.Header().Get("X-Upstream-Error"))
	}
}
