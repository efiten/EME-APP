package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

// config holds everything that tests replace: upstream URLs, timeout and clock.
type config struct {
	version string
	timeout time.Duration
	now     func() time.Time

	w3szURL, n6nuURL, n0ukURL string
}

func defaultConfig(version string) config {
	return config{
		version: version,
		timeout: defaultTimeout,
		now:     time.Now,
		w3szURL: "https://w3sz.com/export_livecq3.php",
		n6nuURL: "https://livecq.n6nu.org/api/spots?limit=200&offset=0&minutes=60&path=eme&lists=0",
		n0ukURL: "https://www.chris.org/cgi-bin/jt65emeA",
	}
}

// sources maps each browser-facing path to its upstream. The intervals are the same as
// the Android app uses (see CONTRIBUTING.md) and apply to all web users together.
func (c config) sources() map[string]source {
	return map[string]source{
		"/api/livecq/w3sz": {name: "w3sz", url: c.w3szURL, contentType: "text/plain; charset=utf-8",
			maxAge: 120 * time.Second, minInterval: 30 * time.Second,
			method: http.MethodPost, reqType: "application/x-www-form-urlencoded", reqBody: "time_period=1d",
			check: checkW3sz},
		"/api/livecq/n6nu": {name: "n6nu", url: c.n6nuURL, contentType: "application/json; charset=utf-8",
			maxAge: 120 * time.Second, minInterval: 30 * time.Second,
			accept: "application/json", check: checkN6nu},
		"/api/n0uk": {name: "n0uk", url: c.n0ukURL, contentType: "text/html; charset=utf-8",
			maxAge: 90 * time.Second, minInterval: 90 * time.Second},
	}
}

func newHandler(cfg config) http.Handler {
	if cfg.now == nil {
		cfg.now = time.Now
	}
	if cfg.timeout <= 0 {
		cfg.timeout = defaultTimeout
	}
	ua := "EME-ON8AR-Web/" + cfg.version
	mux := http.NewServeMux()
	for path, src := range cfg.sources() {
		c := newCache(src, ua, cfg.timeout, cfg.now)
		ctype := src.contentType
		mux.HandleFunc(path, allowGetHead(func(w http.ResponseWriter, r *http.Request) {
			res := c.get()
			h := w.Header()
			h.Set("Cache-Control", "no-store")
			if res.errText != "" {
				h.Set("X-Upstream-Error", res.errText)
			}
			if res.status != http.StatusOK {
				h.Set("Content-Type", "text/plain; charset=utf-8")
				w.WriteHeader(res.status)
				w.Write([]byte(res.errText))
				return
			}
			h.Set("Content-Type", ctype)
			h.Set("X-Fetched-At", strconv.FormatInt(res.fetchedAt.UnixMilli(), 10))
			w.Write(res.body)
		}))
	}
	versionJSON, _ := json.Marshal(map[string]string{"version": cfg.version})
	mux.HandleFunc("/api/version", allowGetHead(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(versionJSON)
	}))
	return mux
}

func allowGetHead(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		next(w, r)
	}
}

func main() {
	listen := flag.String("listen", "127.0.0.1:5200", "listen address")
	version := flag.String("version", "dev", "version name, sent in the User-Agent and /api/version")
	flag.Parse()

	srv := &http.Server{
		Addr:              *listen,
		Handler:           newHandler(defaultConfig(*version)),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		srv.Shutdown(ctx)
	}()

	log.Printf("listening on %s version=%s", *listen, *version)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
