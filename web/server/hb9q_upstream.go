package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// Login backoff, as LoginBackoff.kt.
const (
	loginBackoffBase     = 5 * time.Second
	loginBackoffMax      = 300 * time.Second
	loginBackoffMaxShift = 6
)

var (
	errLoginFailed    = errors.New("HB9Q login failed")
	errSessionExpired = errors.New("HB9Q session expired")
)

// loginBackoff delays new login attempts after failures. Not safe for concurrent use.
type loginBackoff struct {
	failures      int
	cooldownUntil time.Time
}

// retryAfter returns how long a login must still wait; 0 means a login may be tried.
func (b *loginBackoff) retryAfter(now time.Time) time.Duration {
	if remaining := b.cooldownUntil.Sub(now); remaining > 0 {
		return remaining
	}
	return 0
}

func (b *loginBackoff) onSuccess() {
	b.failures = 0
	b.cooldownUntil = time.Time{}
}

func (b *loginBackoff) onFailure(now time.Time) {
	b.failures++
	shift := min(b.failures-1, loginBackoffMaxShift)
	b.cooldownUntil = now.Add(min(loginBackoffBase<<shift, loginBackoffMax))
}

// hb9qConn is one session with logger.hb9q.ch (own cookie jar), as Hb9qClient.kt.
// Unlike the app it does not log in again by itself: the password is not kept.
type hb9qConn struct {
	baseURL   string
	pageURL   string
	userAgent string
	client    *http.Client
}

func newHb9qConn(baseURL, userAgent string, timeout time.Duration) *hb9qConn {
	jar, _ := cookiejar.New(nil)
	return &hb9qConn{
		baseURL:   baseURL,
		pageURL:   baseURL + "/",
		userAgent: userAgent,
		client: &http.Client{
			Timeout: timeout,
			Jar:     jar,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

var (
	inputTagRe  = regexp.MustCompile(`(?is)<input\b[^>]*>`)
	attrRe      = regexp.MustCompile(`(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))`)
	swordfischN = "swordfisch"
)

// findToken returns the value of input[name=swordfisch], whatever the attribute order or quoting.
func findToken(page []byte) (string, bool) {
	for _, tag := range inputTagRe.FindAll(page, -1) {
		var name, value string
		var hasValue bool
		for _, m := range attrRe.FindAllSubmatch(tag, -1) {
			v := string(m[2]) + string(m[3]) + string(m[4])
			switch strings.ToLower(string(m[1])) {
			case "name":
				name = v
			case "value":
				value, hasValue = v, true
			}
		}
		if name == swordfischN {
			return value, hasValue
		}
	}
	return "", false
}

type hb9qResponse struct {
	code int
	body []byte
}

// request sends one request. A 3xx gives an empty body, as in Hb9qClient.request. A 3xx after the
// login POST is followed here with a GET, so the cookies are kept.
func (c *hb9qConn) request(ctx context.Context, method, rawURL, formBody string) (hb9qResponse, error) {
	var body io.Reader
	if formBody != "" {
		body = strings.NewReader(formBody)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, body)
	if err != nil {
		return hb9qResponse{}, errors.New("upstream request error")
	}
	req.Header.Set("User-Agent", c.userAgent)
	if method == http.MethodPost {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	}
	resp, err := c.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return hb9qResponse{}, ctx.Err()
		}
		var ue *url.Error
		if errors.As(err, &ue) && ue.Timeout() {
			return hb9qResponse{}, errors.New("upstream timeout")
		}
		return hb9qResponse{}, errors.New("upstream unreachable")
	}
	defer resp.Body.Close()
	code := resp.StatusCode
	if code >= 400 {
		return hb9qResponse{}, fmt.Errorf("%s: HTTP %d", shortName(rawURL), code)
	}
	var raw []byte
	if code < 300 || code > 399 {
		raw, err = io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
		if err != nil {
			return hb9qResponse{}, errors.New("upstream read error")
		}
		if len(raw) > maxBodyBytes {
			return hb9qResponse{}, errors.New("upstream response too large")
		}
	}
	if code >= 300 && code <= 399 && method == http.MethodPost && rawURL == c.pageURL {
		next := c.pageURL
		if loc := resp.Header.Get("Location"); loc != "" {
			base, _ := url.Parse(rawURL)
			ref, perr := base.Parse(loc)
			if perr != nil {
				return hb9qResponse{}, errors.New("upstream request error")
			}
			next = ref.String()
		}
		return c.request(ctx, http.MethodGet, next, "")
	}
	return hb9qResponse{code: code, body: raw}, nil
}

// shortName: "https://logger.hb9q.ch/rpc/logger/logger_lst.cfm?x" gives "logger_lst" (for error text).
func shortName(rawURL string) string {
	s, _, _ := strings.Cut(rawURL, "?")
	s = s[strings.LastIndex(s, "/")+1:]
	s = strings.TrimSuffix(s, ".cfm")
	if s == "" {
		return "HB9Q"
	}
	return s
}

func (c *hb9qConn) login(ctx context.Context, login, password string) error {
	page, err := c.request(ctx, http.MethodGet, c.pageURL, "")
	if err != nil {
		return err
	}
	token, ok := findToken(page.body)
	if !ok {
		return errors.New("HB9Q: login form not found")
	}
	form := url.Values{}
	form.Set("swordfisch", token)
	form.Set("txtLoginName", login)
	form.Set("txtPwd", password)
	result, err := c.request(ctx, http.MethodPost, c.pageURL, form.Encode())
	if err != nil {
		return err
	}
	// After a good login the page has a logout link.
	if !bytes.Contains(result.body, []byte("srv=logout")) {
		return errLoginFailed
	}
	return nil
}

// messages returns the JSON of logger_lst.cfm for one band.
func (c *hb9qConn) messages(ctx context.Context, bandID int) ([]byte, error) {
	u := fmt.Sprintf("%s/rpc/logger/logger_lst.cfm?vBand=%d&vSearch=&vSearchDt=0", c.baseURL, bandID)
	resp, err := c.request(ctx, http.MethodPost, u, "")
	if err != nil {
		return nil, err
	}
	if (resp.code >= 300 && resp.code <= 399) || !bytes.HasPrefix(bytes.TrimLeftFunc(resp.body, unicode.IsSpace), []byte("{")) {
		return nil, errSessionExpired
	}
	return resp.body, nil
}

// logout is best effort: errors are ignored.
func (c *hb9qConn) logout(ctx context.Context) {
	_, _ = c.request(ctx, http.MethodGet, c.pageURL+"?srv=logout", "")
}
