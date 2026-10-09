package usage

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func testPath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "usage-stats.json")
}

// TestOpenMissingFile: a fresh install has no stats file; Open succeeds with
// an empty store, unknown profiles read back zero, and nothing is written
// until something is recorded.
func TestOpenMissingFile(t *testing.T) {
	path := testPath(t)
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if got := s.Get("nope"); got != (Snapshot{}) {
		t.Errorf("Get(unknown) = %+v, want the zero Snapshot", got)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("Open created the file before any record: stat err = %v", err)
	}
}

// TestAddSessionAndBytes: counters accumulate in the schema's shape and land
// on disk as versioned JSON with RFC 3339 timestamps.
func TestAddSessionAndBytes(t *testing.T) {
	path := testPath(t)
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.AddSession("abc123"); err != nil {
		t.Fatalf("AddSession: %v", err)
	}
	if err := s.AddBytes("abc123", 123456, 654321); err != nil {
		t.Fatalf("AddBytes: %v", err)
	}
	if err := s.AddSession("abc123"); err != nil {
		t.Fatalf("AddSession: %v", err)
	}
	if err := s.AddBytes("abc123", 1, 2); err != nil {
		t.Fatalf("AddBytes: %v", err)
	}

	got := s.Get("abc123")
	if got.TotalUp != 123457 || got.TotalDown != 654323 {
		t.Errorf("totals = %d/%d, want 123457/654323", got.TotalUp, got.TotalDown)
	}
	if got.Sessions != 2 {
		t.Errorf("sessions = %d, want 2 (AddBytes must not count as a session)", got.Sessions)
	}
	first, err := time.Parse(time.RFC3339, got.FirstUsed)
	if err != nil {
		t.Fatalf("first_used %q is not RFC 3339: %v", got.FirstUsed, err)
	}
	last, err := time.Parse(time.RFC3339, got.LastUsed)
	if err != nil {
		t.Fatalf("last_used %q is not RFC 3339: %v", got.LastUsed, err)
	}
	if last.Before(first) {
		t.Errorf("last_used %v is before first_used %v", last, first)
	}

	// The file must match the schema's document shape.
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read stats file: %v", err)
	}
	var doc struct {
		Version int `json:"version"`
		Servers map[string]struct {
			TotalUp   uint64 `json:"total_up"`
			TotalDown uint64 `json:"total_down"`
			Sessions  uint64 `json:"sessions"`
			FirstUsed string `json:"first_used"`
			LastUsed  string `json:"last_used"`
		} `json:"servers"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("stats file is not valid JSON: %v\n%s", err, data)
	}
	if doc.Version != 1 {
		t.Errorf("file version = %d, want 1", doc.Version)
	}
	entry, ok := doc.Servers["abc123"]
	if !ok {
		t.Fatalf("servers map has no abc123 entry: %s", data)
	}
	if entry.TotalUp != 123457 || entry.TotalDown != 654323 || entry.Sessions != 2 {
		t.Errorf("file entry = %+v", entry)
	}
}

// TestPersistenceAcrossReopen: what was counted survives closing and
// reopening the store — the across-restarts property the schema demands.
func TestPersistenceAcrossReopen(t *testing.T) {
	path := testPath(t)
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.AddSession("p1"); err != nil {
		t.Fatalf("AddSession: %v", err)
	}
	if err := s.AddBytes("p1", 10, 20); err != nil {
		t.Fatalf("AddBytes: %v", err)
	}
	before := s.Get("p1")

	s2, err := Open(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	after := s2.Get("p1")
	if after != before {
		t.Errorf("after reopen = %+v, want %+v", after, before)
	}

	// Accumulation continues from the persisted state, not from zero.
	if err := s2.AddBytes("p1", 5, 5); err != nil {
		t.Fatalf("AddBytes after reopen: %v", err)
	}
	got := s2.Get("p1")
	if got.TotalUp != 15 || got.TotalDown != 25 || got.Sessions != 1 {
		t.Errorf("after another AddBytes: %+v, want up 15 down 25 sessions 1", got)
	}
	if got.FirstUsed != before.FirstUsed {
		t.Errorf("first_used moved: %q -> %q", before.FirstUsed, got.FirstUsed)
	}
}

// TestConcurrentMutations: parallel session and byte accounting lands exactly,
// through the lock and the atomic rename.
func TestConcurrentMutations(t *testing.T) {
	path := testPath(t)
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	const goroutines, rounds = 8, 10
	var wg sync.WaitGroup
	errs := make(chan error, goroutines)
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < rounds; i++ {
				if err := s.AddSession("hot"); err != nil {
					errs <- err
					return
				}
				if err := s.AddBytes("hot", 100, 200); err != nil {
					errs <- err
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	if err := <-errs; err != nil {
		t.Fatalf("concurrent mutation: %v", err)
	}

	wantSessions := uint64(goroutines * rounds)
	got := s.Get("hot")
	if got.Sessions != wantSessions || got.TotalUp != 100*wantSessions || got.TotalDown != 200*wantSessions {
		t.Errorf("totals = %+v, want %d sessions / %d up / %d down",
			got, wantSessions, 100*wantSessions, 200*wantSessions)
	}

	// And the file agrees, so the atomic writes did not lose any of it.
	s2, err := Open(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if got := s2.Get("hot"); got.Sessions != wantSessions || got.TotalUp != 100*wantSessions {
		t.Errorf("reopened totals = %+v, want %d sessions / %d up", got, wantSessions, 100*wantSessions)
	}
}

// TestOpenRejectsBadFiles: malformed JSON and unknown versions are errors, not
// silently reset history.
func TestOpenRejectsBadFiles(t *testing.T) {
	path := testPath(t)
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path); err == nil {
		t.Error("Open accepted a malformed stats file")
	}

	if err := os.WriteFile(path, []byte(`{"version": 99, "servers": {}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path); err == nil {
		t.Error("Open accepted a file from an unknown schema version")
	}
}

// TestOpenToleratesEmptyFile: a zero-byte file (a tool that pre-creates the
// path, say) counts as no history rather than a parse failure.
func TestOpenToleratesEmptyFile(t *testing.T) {
	path := testPath(t)
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open on an empty file: %v", err)
	}
	if err := s.AddSession("p"); err != nil {
		t.Fatalf("AddSession: %v", err)
	}
	if got := s.Get("p"); got.Sessions != 1 {
		t.Errorf("sessions = %d, want 1", got.Sessions)
	}
}

// TestAtomicWriteLeavesNoTempFiles: the temp-and-rename write is invisible
// afterwards — only the stats file is in the directory.
func TestAtomicWriteLeavesNoTempFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "usage-stats.json")
	s, err := Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	for i := 0; i < 5; i++ {
		if err := s.AddBytes("p", 1, 1); err != nil {
			t.Fatalf("AddBytes: %v", err)
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if e.Name() != "usage-stats.json" {
			t.Errorf("leftover file in stats directory: %s", e.Name())
		}
	}
}

// TestAddBytesCreatesProfile: byte accounting for a profile never seen by
// AddSession still yields a well-formed record.
func TestAddBytesCreatesProfile(t *testing.T) {
	s, err := Open(testPath(t))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.AddBytes("only-bytes", 7, 8); err != nil {
		t.Fatalf("AddBytes: %v", err)
	}
	got := s.Get("only-bytes")
	if got.TotalUp != 7 || got.TotalDown != 8 || got.Sessions != 0 {
		t.Errorf("snapshot = %+v, want 7/8 with 0 sessions", got)
	}
	if got.FirstUsed == "" || got.LastUsed == "" {
		t.Errorf("timestamps not stamped: %+v", got)
	}
}
