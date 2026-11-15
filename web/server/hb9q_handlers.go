package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	hb9qCookie          = "eme_hb9q"
	hb9qCookiePath      = "/api/hb9q/"
	hb9qMessagesMaxAge  = 20 * time.Second // ChatStore.MESSAGES_REFRESH_MS
	hb9qSessionIdle     = 30 * time.Minute
	hb9qLoginLimit      = 10 // HB9Q login attempts per hb9qLoginWindow, whole server
	hb9qLoginWindow     = time.Minute
	hb9qLoginBodyMax    = 4096
	hb9qHandlerDeadline = 50 * time.Second
	hb9qMinBand         = 1 // Hb9qBand ids in ChatMessage.kt
	hb9qMaxBand         = 13
)

// hb9qMessages is the last logger_lst attempt of one band in one session.
type hb9qMessages struct {
	attemptAt time.Time
	body      []byte
	errText   string
}

// webSession is one browser session with its own HB9Q session. mu serialises the use of conn
// and cache, so parallel requests of one session wait and then get the cached answer.
type webSession struct {
	mu    sync.Mutex
	conn  *hb9qConn
	cache map[int]hb9qMessages
	dead  atomic.Bool // set when the session is removed while a request may still hold it

	lastUsed time.Time // guarded by hb9qService.mu
}

// hb9qService keeps the web sessions. mu guards sessions, backoffs, logins and lastUsed;
// it is never held during a request to HB9Q.
type hb9qService struct {
	baseURL, userAgent string
	timeout            time.Duration
	now                func() time.Time

	mu       sync.Mutex
	sessions map[string]*webSession
	backoffs map[string]*loginBackoff // key: login in upper case
	logins   []time.Time              // HB9Q login attempts in the last hb9qLoginWindow
}

func newHb9qService(baseURL, userAgent string, timeout time.Duration, now func() time.Time) *hb9qService {
	return &hb9qService{
		baseURL: baseURL, userAgent: userAgent, timeout: timeout, now: now,
		sessions: map[string]*webSession{},
		backoffs: map[string]*loginBackoff{},
	}
}

func (s *hb9qService) register(mux *http.ServeMux) {
	mux.HandleFunc("/api/hb9q/login", hb9qMethod(http.MethodPost, s.handleLogin))
	mux.HandleFunc("/api/hb9q/messages", hb9qMethod(http.MethodGet, s.handleMessages))
	mux.HandleFunc("/api/hb9q/logout", hb9qMethod(http.MethodPost, s.handleLogout))
}

// hb9qMethod sets Cache-Control on every answer, refuses other methods and bounds the handler time.
func hb9qMethod(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.Method != method {
			w.Header().Set("Allow", method)
			writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method_not_allowed"})
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), hb9qHandlerDeadline)
		defer cancel()
		next(w, r.WithContext(ctx))
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, name string) {
	writeJSON(w, code, map[string]any{"error": name})
}

func retryMs(d time.Duration) int64 {
	return (d + time.Millisecond - 1).Milliseconds()
}

// sweep removes idle sessions and old backoff entries. Caller holds s.mu.
func (s *hb9qService) sweep(now time.Time) {
	for id, sess := range s.sessions {
		if now.Sub(sess.lastUsed) >= hb9qSessionIdle {
			sess.dead.Store(true)
			delete(s.sessions, id)
		}
	}
	for key, b := range s.backoffs {
		if now.Sub(b.cooldownUntil) > loginBackoffMax {
			delete(s.backoffs, key)
		}
	}
	keep := s.logins[:0]
	for _, t := range s.logins {
		if now.Sub(t) < hb9qLoginWindow {
			keep = append(keep, t)
		}
	}
	s.logins = keep
}

func cookieID(r *http.Request) string {
	if c, err := r.Cookie(hb9qCookie); err == nil {
		return c.Value
	}
	return ""
}

func newSessionID() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand does not fail on supported platforms
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func (s *hb9qService) handleLogin(w http.ResponseWriter, r *http.Request) {
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		writeErr(w, http.StatusUnsupportedMediaType, "unsupported_media_type")
		return
	}
	var req struct {
		Login    string `json:"login"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, hb9qLoginBodyMax)).Decode(&req); err != nil ||
		req.Login == "" || req.Password == "" {
		writeErr(w, http.StatusBadRequest, "bad_request")
		return
	}
	key := strings.ToUpper(req.Login)

	s.mu.Lock()
	now := s.now()
	s.sweep(now)
	if b := s.backoffs[key]; b != nil && b.retryAfter(now) > 0 {
		wait := b.retryAfter(now)
		s.mu.Unlock()
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "cooldown", "retryAfterMs": retryMs(wait)})
		return
	}
	if len(s.logins) >= hb9qLoginLimit {
		wait := s.logins[0].Add(hb9qLoginWindow).Sub(now)
		s.mu.Unlock()
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "busy", "retryAfterMs": retryMs(wait)})
		return
	}
	s.logins = append(s.logins, now)
	s.mu.Unlock()

	conn := newHb9qConn(s.baseURL, s.userAgent, s.timeout)
	err := conn.login(r.Context(), req.Login, req.Password)

	s.mu.Lock()
	defer s.mu.Unlock()
	now = s.now()
	if err != nil {
		b := s.backoffs[key]
		if b == nil {
			b = &loginBackoff{}
			s.backoffs[key] = b
		}
		b.onFailure(now)
		if errors.Is(err, errLoginFailed) {
			writeErr(w, http.StatusUnauthorized, "login_failed")
			return
		}
		log.Printf("hb9q login: %v", err)
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": "upstream", "detail": err.Error()})
		return
	}
	delete(s.backoffs, key)
	if old, ok := s.sessions[cookieID(r)]; ok {
		old.dead.Store(true)
		delete(s.sessions, cookieID(r))
	}
	id := newSessionID()
	s.sessions[id] = &webSession{conn: conn, cache: map[int]hb9qMessages{}, lastUsed: now}
	http.SetCookie(w, &http.Cookie{
		Name: hb9qCookie, Value: id, Path: hb9qCookiePath,
		HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode,
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// session returns the live session of the request and marks it as used, or nil.
func (s *hb9qService) session(r *http.Request) *webSession {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	s.sweep(now)
	sess := s.sessions[cookieID(r)]
	if sess != nil {
		sess.lastUsed = now
	}
	return sess
}

func (s *hb9qService) remove(r *http.Request, sess *webSession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess.dead.Store(true)
	if id := cookieID(r); s.sessions[id] == sess {
		delete(s.sessions, id)
	}
}

func (s *hb9qService) handleMessages(w http.ResponseWriter, r *http.Request) {
	band, err := strconv.Atoi(r.URL.Query().Get("band"))
	if err != nil || band < hb9qMinBand || band > hb9qMaxBand {
		writeErr(w, http.StatusBadRequest, "bad_band")
		return
	}
	sess := s.session(r)
	if sess == nil {
		writeErr(w, http.StatusUnauthorized, "no_session")
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if sess.dead.Load() {
		writeErr(w, http.StatusUnauthorized, "no_session")
		return
	}
	m, ok := sess.cache[band]
	if now := s.now(); !ok || now.Sub(m.attemptAt) >= hb9qMessagesMaxAge {
		body, err := sess.conn.messages(r.Context(), band)
		if errors.Is(err, errSessionExpired) {
			s.remove(r, sess)
			writeErr(w, http.StatusUnauthorized, "session_expired")
			return
		}
		m = hb9qMessages{attemptAt: now, body: body}
		if err != nil {
			log.Printf("hb9q messages: %v", err)
			m.errText = err.Error()
		}
		sess.cache[band] = m
	}
	if m.errText != "" {
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": "upstream", "detail": m.errText})
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/plain; charset=utf-8")
	h.Set("X-Fetched-At", strconv.FormatInt(m.attemptAt.UnixMilli(), 10))
	w.Write(m.body)
}

func (s *hb9qService) handleLogout(w http.ResponseWriter, r *http.Request) {
	if sess := s.session(r); sess != nil {
		s.remove(r, sess)
		sess.mu.Lock()
		sess.conn.logout(r.Context())
		sess.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{
		Name: hb9qCookie, Value: "", Path: hb9qCookiePath, MaxAge: -1,
		HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode,
	})
	w.WriteHeader(http.StatusNoContent)
}
