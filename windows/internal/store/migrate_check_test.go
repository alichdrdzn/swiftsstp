package store

import (
	"os"
	"path/filepath"
	"testing"
)

func freshHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("SWIFTSSTP_HOME", "")
	t.Setenv("SSTPFLASH_HOME", "")
	return home
}

func TestDirMigratesLegacy(t *testing.T) {
	home := freshHome(t)
	legacy := filepath.Join(home, ".config", "SSTPflash")
	if err := os.MkdirAll(legacy, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacy, "profiles.json"), []byte(`{"version":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(home, ".config", "SwiftSSTP")
	if dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	if _, err := os.Stat(filepath.Join(dir, "profiles.json")); err != nil {
		t.Fatalf("profiles.json not migrated: %v", err)
	}
	// Second call is stable (no re-migration).
	dir2, err := Dir()
	if err != nil || dir2 != want {
		t.Fatalf("second Dir() = %q, %v", dir2, err)
	}
}

func TestDirFreshInstall(t *testing.T) {
	home := freshHome(t)
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".config", "SwiftSSTP"); dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	if _, err := os.Stat(filepath.Join(home, ".config", "SSTPflash")); !os.IsNotExist(err) {
		t.Fatalf("legacy dir should not exist: %v", err)
	}
}

func TestDirKeepsNewWhenBothExist(t *testing.T) {
	home := freshHome(t)
	for _, d := range []string{"SSTPflash", "SwiftSSTP"} {
		p := filepath.Join(home, ".config", d)
		if err := os.MkdirAll(p, 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(p, "profiles.json"), []byte(d), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".config", "SwiftSSTP"); dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "profiles.json"))
	if string(b) != "SwiftSSTP" {
		t.Fatalf("new dir contents clobbered: %q", b)
	}
}

func TestDirLegacyEnvFallback(t *testing.T) {
	home := freshHome(t)
	legacyHome := filepath.Join(home, "oldcfg")
	t.Setenv("SSTPFLASH_HOME", legacyHome)
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if dir != legacyHome {
		t.Fatalf("Dir() = %q, want legacy env %q", dir, legacyHome)
	}
	// New env wins when both are set.
	newHome := filepath.Join(home, "newcfg")
	t.Setenv("SWIFTSSTP_HOME", newHome)
	dir, err = Dir()
	if err != nil {
		t.Fatal(err)
	}
	if dir != newHome {
		t.Fatalf("Dir() = %q, want %q", dir, newHome)
	}
}
