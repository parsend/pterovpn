package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveQUICCAPath(t *testing.T) {
	if got := ResolveQUICCAPath("/home/u/pteravpn", "ca.pem"); got != filepath.Join("/home/u/pteravpn", "ca.pem") {
		t.Fatalf("relative: %q", got)
	}
	if got := ResolveQUICCAPath("/cfg", "/etc/ssl/foo.pem"); got != "/etc/ssl/foo.pem" {
		t.Fatalf("abs: %q", got)
	}
	if got := ResolveQUICCAPath("/cfg", "  "); got != "" {
		t.Fatalf("empty: %q", got)
	}
}

func TestLoadQUICCAPoolNoCerts(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "empty.pem")
	if err := os.WriteFile(p, []byte("hello"), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := LoadQUICCAPool(p)
	if err == nil {
		t.Fatal("want error")
	}
}
