package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const attemptsFile = "last-attempt.json"

// attemptStore keeps the last upstream attempt per source on disk, so that a restart
// (deploy, rollback) does not reset the polling gate. A nil store does nothing.
type attemptStore struct {
	path string

	mu    sync.Mutex
	times map[string]int64 // source name -> unix milliseconds
}

// loadAttempts reads the file. A missing or corrupt file gives an empty store.
func loadAttempts(path string) *attemptStore {
	s := &attemptStore{path: path, times: map[string]int64{}}
	data, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("state: cannot read %s: %v", path, err)
		}
		return s
	}
	if err := json.Unmarshal(data, &s.times); err != nil {
		log.Printf("state: ignoring corrupt %s: %v", path, err)
		s.times = map[string]int64{}
	}
	return s
}

// last returns the stored attempt time of a source, ignoring times after now.
func (s *attemptStore) last(name string, now time.Time) (time.Time, bool) {
	if s == nil {
		return time.Time{}, false
	}
	s.mu.Lock()
	ms, ok := s.times[name]
	s.mu.Unlock()
	if !ok {
		return time.Time{}, false
	}
	t := time.UnixMilli(ms)
	if t.After(now) {
		log.Printf("state: ignoring attempt time in the future for %s", name)
		return time.Time{}, false
	}
	return t, true
}

// record stores the attempt and rewrites the file atomically (temp file, then rename).
func (s *attemptStore) record(name string, t time.Time) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.times[name] = t.UnixMilli()
	data, _ := json.Marshal(s.times)
	tmp, err := os.CreateTemp(filepath.Dir(s.path), attemptsFile+".tmp-*")
	if err == nil {
		_, err = tmp.Write(data)
		if cerr := tmp.Close(); err == nil {
			err = cerr
		}
		if err == nil {
			err = os.Rename(tmp.Name(), s.path)
		}
		if err != nil {
			os.Remove(tmp.Name())
		}
	}
	if err != nil {
		log.Printf("state: cannot write %s: %v", s.path, err)
	}
}

// attachAttempts seeds the gate from the store and records every later attempt in it.
func (c *cache) attachAttempts(s *attemptStore) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.attempts = s
	if t, ok := s.last(c.src.name, c.now()); ok {
		c.lastAttempt, c.hasAttempt = t, true
	}
}
