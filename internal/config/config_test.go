package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSaveLoadDelete(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	c := Config{Server: "1.2.3.4:443", Token: "secret"}
	if err := Save("test", c); err != nil {
		t.Fatal(err)
	}
	got, err := LoadByName("test")
	if err != nil {
		t.Fatal(err)
	}
	if got.Server != c.Server || got.Token != c.Token {
		t.Errorf("got %+v", got)
	}
	if err := Delete("test"); err != nil {
		t.Fatal(err)
	}
	_, err = LoadByName("test")
	if err == nil {
		t.Error("want error after delete")
	}
}

func TestLoadOldConfigKeepsDefaults(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	configDir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(configDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(configDir, "legacy.json"),
		[]byte(`{"server":"1.1.1.1:443","token":"legacy","routes":"","exclude":""}`),
		0600,
	); err != nil {
		t.Fatal(err)
	}

	got, err := Load(filepath.Join(configDir, "legacy.json"))
	if err != nil {
		t.Fatal(err)
	}
	if got.Transport != "" {
		t.Errorf("transport=%q", got.Transport)
	}
	if got.TLSName != "" {
		t.Errorf("tlsName=%q", got.TLSName)
	}
}

func TestSaveLoadWithTransportFields(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	c := Config{
		Server:    "1.2.3.4:443",
		Token:     "secret",
		Transport: "tls",
		TLSName:   "vpn.example.com",
	}
	if err := Save("transport", c); err != nil {
		t.Fatal(err)
	}
	c2, err := LoadByName("transport")
	if err != nil {
		t.Fatal(err)
	}
	if c2.Transport != "tls" {
		t.Errorf("transport=%q", c2.Transport)
	}
	if c2.TLSName != "vpn.example.com" {
		t.Errorf("tlsName=%q", c2.TLSName)
	}
}

func TestSanitizeName(t *testing.T) {
	for _, tc := range []struct {
		in, want string
	}{
		{"abc", "abc"},
		{"a-b_c1", "a-b_c1"},
		{"  x  ", "x"},
		{"a b", "ab"},
		{"", "default"},
		{"!!!", "default"},
	} {
		got := SanitizeName(tc.in)
		if got != tc.want {
			t.Errorf("SanitizeName(%q)=%q want %q", tc.in, got, tc.want)
		}
	}
}

func TestProtection(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	p := ProtectionOptions{PadS1: 32, JunkCount: 3}
	if err := SaveProtection(p); err != nil {
		t.Fatal(err)
	}
	got, err := LoadProtection()
	if err != nil {
		t.Fatal(err)
	}
	if got.PadS1 != p.PadS1 || got.JunkCount != p.JunkCount {
		t.Errorf("got %+v", got)
	}
}

func TestLoadProtectionNotExist(t *testing.T) {
	dir := t.TempDir()
	os.RemoveAll(filepath.Join(dir, "pteravpn"))
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	got, err := LoadProtection()
	if err != nil {
		t.Fatal(err)
	}
	if got.PadS1 != 0 || got.JunkCount != 0 {
		t.Errorf("want zero value, got %+v", got)
	}
}
