// Package usage keeps the cumulative per-profile counters stored in
// usage-stats.json beside profiles.json (docs/PROFILE_SCHEMA.md): inner bytes
// carried up and down, session counts, and first/last use, keyed by profile id
// and durable across restarts.
//
// Every mutation is written through to the file — through a temp file and a
// rename, so the file on disk is always one complete document and a crash
// cannot leave truncated history behind. That also means no Save call to
// remember; and it means AddBytes should be called with accumulated deltas
// (once per counter poll, say), not once per packet.
//
// A Store assumes one writer process per file, which matches how the apps
// keep it next to their profiles file.
package usage

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Version is the on-disk document version (docs/PROFILE_SCHEMA.md).
const Version = 1

// Snapshot is one profile's cumulative counters, in the usage-stats.json
// shape. FirstUsed/LastUsed are RFC 3339 timestamps in UTC; an unseen profile
// reads back as the zero Snapshot.
type Snapshot struct {
	TotalUp   uint64 `json:"total_up"`
	TotalDown uint64 `json:"total_down"`
	Sessions  uint64 `json:"sessions"`
	FirstUsed string `json:"first_used"`
	LastUsed  string `json:"last_used"`
}

// document is the file as a whole: the version and the per-profile map keyed
// by profile id.
type document struct {
	Version int                 `json:"version"`
	Servers map[string]Snapshot `json:"servers"`
}

// Store is an open usage-stats file. Safe for concurrent use: every exported
// method serializes on the store's lock, and the file is rewritten atomically.
type Store struct {
	mu   sync.Mutex
	doc  document
	path string
}

// Open reads the usage-stats file at path. A missing file is not an error — it
// is a fresh install — and yields an empty store that is created on the first
// mutation. A malformed file, or one written by a newer schema version, is an
// error: silently discarding a user's history is worse than saying so.
func Open(path string) (*Store, error) {
	s := &Store{
		path: path,
		doc:  document{Version: Version, Servers: make(map[string]Snapshot)},
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("usage: read %s: %w", path, err)
	}
	var doc document
	if len(bytes.TrimSpace(data)) > 0 {
		if err := json.Unmarshal(data, &doc); err != nil {
			return nil, fmt.Errorf("usage: parse %s: %w", path, err)
		}
	}
	if doc.Version == 0 {
		doc.Version = Version
	}
	if doc.Version != Version {
		return nil, fmt.Errorf("usage: %s has version %d, this build writes %d", path, doc.Version, Version)
	}
	if doc.Servers == nil {
		doc.Servers = make(map[string]Snapshot)
	}
	s.doc = doc
	return s, nil
}

// AddSession records one successful tunnel establishment for profileID: it
// increments the session count and stamps the use. Counters and timestamps
// also appear for a profile seen here for the first time.
func (s *Store) AddSession(profileID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	e := s.doc.Servers[profileID]
	e.Sessions++
	s.stamp(&e)
	s.doc.Servers[profileID] = e
	return s.persistLocked()
}

// AddBytes adds up/down inner bytes carried for profileID. Pass the delta
// since the last call (e.g. what tunnel.Session.Counters grew by), not a
// running total — the counters here are cumulative already. It does not touch
// the session count.
func (s *Store) AddBytes(profileID string, up, down uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	e := s.doc.Servers[profileID]
	e.TotalUp += up
	e.TotalDown += down
	s.stamp(&e)
	s.doc.Servers[profileID] = e
	return s.persistLocked()
}

// Get returns profileID's counters, or the zero Snapshot for a profile with
// no recorded use.
func (s *Store) Get(profileID string) Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.doc.Servers[profileID]
}

// stamp updates the use timestamps of a record being mutated: first_used is
// written when the profile is first seen and never moved, last_used on every
// call. Both are RFC 3339 in UTC, per the schema.
func (s *Store) stamp(e *Snapshot) {
	now := time.Now().UTC().Format(time.RFC3339)
	if e.FirstUsed == "" {
		e.FirstUsed = now
	}
	e.LastUsed = now
}

// persistLocked writes the document out. The write goes to a temp file in the
// same directory and is renamed over the target, so a reader — or a crash —
// never sees a partial document.
func (s *Store) persistLocked() error {
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("usage: create %s: %w", dir, err)
	}
	tmp, err := os.CreateTemp(dir, ".usage-*.json")
	if err != nil {
		return fmt.Errorf("usage: temp file in %s: %w", dir, err)
	}
	enc := json.NewEncoder(tmp)
	enc.SetIndent("", "  ")
	writeErr := enc.Encode(&s.doc)
	if closeErr := tmp.Close(); writeErr == nil {
		writeErr = closeErr
	}
	if writeErr == nil {
		writeErr = os.Rename(tmp.Name(), s.path)
	}
	if writeErr != nil {
		_ = os.Remove(tmp.Name())
		return fmt.Errorf("usage: write %s: %w", s.path, writeErr)
	}
	return nil
}
