package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	defaultTimeout = 15 * time.Second
	maxBodyBytes   = 8 << 20
)

// source describes one upstream site and how often it may be fetched.
// The limits apply to all browsers together, because one cache serves them all.
type source struct {
	name        string
	url         string
	contentType string // Content-Type sent to the browser (the body is always UTF-8)
	maxAge      time.Duration
	minInterval time.Duration

	// Request, exactly as the Android app sends it. Empty method means GET.
	method  string
	reqType string // Content-Type of the request body
	reqBody string
	accept  string
	check   func(body []byte) string // content check on the decoded body; returns error text or ""
}

// result is one answer for a browser.
type result struct {
	status    int // 200, or 502 when there has never been a good copy
	body      []byte
	fetchedAt time.Time // time of the good copy being served
	errText   string    // set when the last attempt failed
}

// cache holds the last good copy of one source. The mutex is held during an upstream
// fetch, so there is at most one fetch at a time and concurrent callers wait for it,
// then see the fresh copy (or the recorded failure) without fetching again.
type cache struct {
	src       source
	userAgent string
	client    *http.Client
	now       func() time.Time

	mu          sync.Mutex
	body        []byte
	hasBody     bool
	fetchedAt   time.Time
	lastAttempt time.Time
	hasAttempt  bool
	errText     string
}

func newCache(src source, userAgent string, timeout time.Duration, now func() time.Time) *cache {
	return &cache{
		src:       src,
		userAgent: userAgent,
		client:    &http.Client{Timeout: timeout},
		now:       now,
	}
}

func (c *cache) get() result {
	c.mu.Lock()
	defer c.mu.Unlock()

	t := c.now()
	// Gate on the last attempt, success or failure, like the app (LiveCqStore.lastFetch):
	// a site that is down is retried at the normal cadence, not at minInterval.
	due := !c.hasAttempt || t.Sub(c.lastAttempt) >= max(c.src.maxAge, c.src.minInterval)
	if due {
		c.fetch(t)
	}
	return c.current()
}

func (c *cache) current() result {
	if !c.hasBody {
		return result{status: http.StatusBadGateway, errText: c.errText}
	}
	return result{status: http.StatusOK, body: c.body, fetchedAt: c.fetchedAt, errText: c.errText}
}

// fetch must be called with c.mu held.
func (c *cache) fetch(attemptAt time.Time) {
	start := time.Now()
	c.lastAttempt, c.hasAttempt = attemptAt, true
	body, status, errText := c.download()
	log.Printf("fetch source=%s status=%d duration=%s", c.src.name, status, time.Since(start).Round(time.Millisecond))
	if errText != "" {
		c.errText = errText
		return
	}
	c.body, c.hasBody, c.fetchedAt, c.errText = body, true, c.now(), ""
}

// download does a plain GET. errText is short and never contains the URL.
func (c *cache) download() (body []byte, status int, errText string) {
	method := c.src.method
	if method == "" {
		method = http.MethodGet
	}
	var reqBody io.Reader
	if c.src.reqBody != "" {
		reqBody = strings.NewReader(c.src.reqBody)
	}
	req, err := http.NewRequest(method, c.src.url, reqBody)
	if err != nil {
		return nil, 0, "upstream request error"
	}
	req.Header.Set("User-Agent", c.userAgent)
	if c.src.reqType != "" {
		req.Header.Set("Content-Type", c.src.reqType)
	}
	if c.src.accept != "" {
		req.Header.Set("Accept", c.src.accept)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, 0, "upstream timeout"
		}
		return nil, 0, "upstream unreachable"
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, resp.StatusCode, fmt.Sprintf("%s HTTP %d", strings.ToUpper(c.src.name), resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
	if err != nil {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, resp.StatusCode, "upstream timeout"
		}
		return nil, resp.StatusCode, "upstream read error"
	}
	if len(raw) > maxBodyBytes {
		return nil, resp.StatusCode, "upstream response too large"
	}
	body = decodeBody(raw, resp.Header.Get("Content-Type"))
	if c.src.check != nil {
		if msg := c.src.check(body); msg != "" {
			return nil, resp.StatusCode, msg
		}
	}
	return body, resp.StatusCode, ""
}

// Content checks as in LiveCqRepository.kt.
func checkW3sz(body []byte) string {
	if bytes.HasPrefix(bytes.TrimLeftFunc(body, unicode.IsSpace), []byte("<")) {
		return "W3SZ: no CSV data"
	}
	return ""
}

func checkN6nu(body []byte) string {
	if !bytes.HasPrefix(bytes.TrimLeftFunc(body, unicode.IsSpace), []byte("{")) {
		return "N6NU: no JSON data"
	}
	return ""
}

// cp1252High maps bytes 0x80-0x9F of windows-1252. The five undefined bytes map to the
// C1 control with the same code point, like browsers do.
var cp1252High = [32]rune{
	0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
	0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
	0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
	0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
}

// decodeBody converts latin1 and windows-1252 to UTF-8 by the charset of the Content-Type.
// Anything else (utf-8, missing, unknown) is passed through unchanged.
func decodeBody(raw []byte, contentType string) []byte {
	_, params, err := mime.ParseMediaType(contentType)
	if err != nil && params == nil {
		return raw
	}
	var win1252 bool
	switch strings.ToLower(strings.TrimSpace(params["charset"])) {
	case "iso-8859-1", "iso8859-1", "iso_8859-1", "latin1", "l1":
	case "windows-1252", "cp1252":
		win1252 = true
	default:
		return raw
	}
	out := make([]byte, 0, len(raw)+len(raw)/8)
	for _, b := range raw {
		r := rune(b)
		if win1252 && b >= 0x80 && b <= 0x9F {
			r = cp1252High[b-0x80]
		}
		out = utf8.AppendRune(out, r)
	}
	return out
}
