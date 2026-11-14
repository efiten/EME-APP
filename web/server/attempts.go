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
	}
	if s.times == nil { // corrupt file, or the JSON text null
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
	writeAtomic(s.path, data)
}

// writeAtomic writes a temp file next to path and renames it over path. Errors are logged.
func writeAtomic(path string, data []byte) {
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".tmp-*")
	if err == nil {
		_, err = tmp.Write(data)
		if cerr := tmp.Close(); err == nil {
			err = cerr
		}
		if err == nil {
			err = os.Rename(tmp.Name(), path)
		}
		if err != nil {
			os.Remove(tmp.Name())
		}
	}
	if err != nil {
		log.Printf("state: cannot write %s: %v", path, err)
	}
}

// copyMeta describes the stored body of a source; size ties the two files together.
type copyMeta struct {
	FetchedAt int64 `json:"fetchedAt"` // unix milliseconds
	Size      int   `json:"size"`
}

// saveCopy stores the last good body and its fetch time. The body is written first, then
// the metadata, so a metadata file always points at a complete body of that size.
func (s *attemptStore) saveCopy(name string, body []byte, fetchedAt time.Time) {
	if s == nil {
		return
	}
	dir := filepath.Dir(s.path)
	writeAtomic(filepath.Join(dir, name+".body"), body)
	meta, _ := json.Marshal(copyMeta{FetchedAt: fetchedAt.UnixMilli(), Size: len(body)})
	writeAtomic(filepath.Join(dir, name+".meta"), meta)
}

// loadCopy returns the stored copy of a source. Missing, corrupt, oversized, mismatching
// or future-dated files give false.
func (s *attemptStore) loadCopy(name string, now time.Time) ([]byte, time.Time, bool) {
	if s == nil {
		return nil, time.Time{}, false
	}
	dir := filepath.Dir(s.path)
	metaData, err := os.ReadFile(filepath.Join(dir, name+".meta"))
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("state: cannot read %s copy: %v", name, err)
		}
		return nil, time.Time{}, false
	}
	var m copyMeta
	if err := json.Unmarshal(metaData, &m); err != nil || m.Size <= 0 || m.Size > maxBodyBytes {
		log.Printf("state: ignoring corrupt %s metadata", name)
		return nil, time.Time{}, false
	}
	body, err := os.ReadFile(filepath.Join(dir, name+".body"))
	if err != nil || len(body) != m.Size {
		log.Printf("state: ignoring %s copy: body missing or does not match its metadata", name)
		return nil, time.Time{}, false
	}
	t := time.UnixMilli(m.FetchedAt)
	if t.After(now) {
		log.Printf("state: ignoring %s copy dated in the future", name)
		return nil, time.Time{}, false
	}
	return body, t, true
}

// attachAttempts seeds the gate from the store and records every later attempt in it.
func (c *cache) attachAttempts(s *attemptStore) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.attempts = s
	if t, ok := s.last(c.src.name, c.now()); ok {
		c.lastAttempt, c.hasAttempt = t, true
	}
	if body, t, ok := s.loadCopy(c.src.name, c.now()); ok {
		c.body, c.hasBody, c.fetchedAt = body, true, t
	}
}
