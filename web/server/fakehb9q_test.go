package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
)

const (
	fakeToken     = "fish123"
	fakeLoginPage = `<html><form method="post"><input type="hidden" name="swordfisch" value="` + fakeToken + `">
<input name="txtLoginName"><input name="txtPwd" type="password"></form></html>`
	fakeLoggedInPage = `<html><a href="/?srv=logout">Logout</a></html>`
)

// fakeHb9q imitates logger.hb9q.ch the way hb9qConn sees it (port of FakeHb9q.kt).
type fakeHb9q struct {
	srv   *httptest.Server
	users map[string]string

	mu          sync.Mutex
	sessions    map[string]string // session id -> logged in user ("" = none)
	nextSession int

	loginPosts         int
	loggerPosts        int
	logouts            int
	lastUserAgent      string
	lastLoggerQuery    string
	lastLoginForm      url.Values
	redirectAfterLogin bool // answer the login POST with 302 to /
	loggerStatus       int  // when set, logger_lst.cfm answers with this status
}

func newFakeHb9q(users map[string]string) *fakeHb9q {
	f := &fakeHb9q{users: users, sessions: map[string]string{}, nextSession: 1}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	return f
}

func (f *fakeHb9q) close()          { f.srv.Close() }
func (f *fakeHb9q) baseURL() string { return f.srv.URL }

// dropSessions forgets every session, like a server that expired them.
func (f *fakeHb9q) dropSessions() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sessions = map[string]string{}
}

func (f *fakeHb9q) sessionCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.nextSession - 1
}

// counts returns the login posts, logger_lst calls and logouts so far.
func (f *fakeHb9q) counts() (loginPosts, loggerPosts, logouts int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.loginPosts, f.loggerPosts, f.logouts
}

func (f *fakeHb9q) setLoggerStatus(code int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.loggerStatus = code
}

func (f *fakeHb9q) handle(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lastUserAgent = r.Header.Get("User-Agent")

	sid, user := "", ""
	if c, err := r.Cookie("CFID"); err == nil {
		if u, ok := f.sessions[c.Value]; ok {
			sid, user = c.Value, u
		}
	}

	switch {
	case r.URL.Path == "/" && r.Method == http.MethodGet && r.URL.RawQuery == "srv=logout":
		f.logouts++
		if sid != "" {
			f.sessions[sid] = ""
		}
		fmt.Fprint(w, fakeLoginPage)
	case r.URL.Path == "/" && r.Method == http.MethodGet:
		switch {
		case user != "":
			fmt.Fprint(w, fakeLoggedInPage)
		case sid != "":
			fmt.Fprint(w, fakeLoginPage)
		default:
			id := fmt.Sprintf("S%d", f.nextSession)
			f.nextSession++
			f.sessions[id] = ""
			http.SetCookie(w, &http.Cookie{Name: "CFID", Value: id, Path: "/"})
			fmt.Fprint(w, fakeLoginPage)
		}
	case r.URL.Path == "/" && r.Method == http.MethodPost:
		f.loginPosts++
		_ = r.ParseForm()
		f.lastLoginForm = r.PostForm
		name := r.PostForm.Get("txtLoginName")
		pw, known := f.users[name]
		ok := sid != "" && r.PostForm.Get("swordfisch") == fakeToken && known && pw == r.PostForm.Get("txtPwd") &&
			strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded")
		if ok {
			f.sessions[sid] = name
			if f.redirectAfterLogin {
				http.Redirect(w, r, "/", http.StatusFound)
				return
			}
			fmt.Fprint(w, fakeLoggedInPage)
			return
		}
		fmt.Fprint(w, fakeLoginPage)
	case r.URL.Path == "/rpc/logger/logger_lst.cfm":
		f.loggerPosts++
		f.lastLoggerQuery = r.URL.RawQuery
		switch {
		case f.loggerStatus != 0:
			w.WriteHeader(f.loggerStatus)
		case user != "":
			fmt.Fprint(w, `{"status":"success","logger":""}`)
		default:
			http.Redirect(w, r, "/", http.StatusFound)
		}
	default:
		http.NotFound(w, r)
	}
}
