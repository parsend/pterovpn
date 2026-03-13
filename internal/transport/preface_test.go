package transport

import (
	"bytes"
	"testing"
)

func TestPrefaceRoundTrip(t *testing.T) {
	for _, name := range []string{NameXOR, NameMTLS} {
		var buf bytes.Buffer
		if err := WritePreface(&buf, name); err != nil {
			t.Fatal(err)
		}
		got, err := ReadPreface(&buf)
		if err != nil {
			t.Fatal(err)
		}
		if got != name {
			t.Fatalf("got %q want %q", got, name)
		}
	}
}

func TestBootstrapBundleRoundTrip(t *testing.T) {
	want := ClientBundle{
		ServerCertPEM: []byte("server-cert"),
		ClientCertPEM: []byte("client-cert"),
		ClientKeyPEM:  []byte("client-key"),
	}
	var buf bytes.Buffer
	if err := WriteBootstrapBundle(&buf, want); err != nil {
		t.Fatal(err)
	}
	got, err := readBootstrapBundle(&buf)
	if err != nil {
		t.Fatal(err)
	}
	if string(got.ServerCertPEM) != string(want.ServerCertPEM) {
		t.Fatalf("server cert mismatch")
	}
	if string(got.ClientCertPEM) != string(want.ClientCertPEM) {
		t.Fatalf("client cert mismatch")
	}
	if string(got.ClientKeyPEM) != string(want.ClientKeyPEM) {
		t.Fatalf("client key mismatch")
	}
}

func TestReadPrefaceRejectsBadMagic(t *testing.T) {
	buf := bytes.NewBuffer([]byte{'B', 'A', 'D', '!', 1, 1})
	if _, err := ReadPreface(buf); err == nil {
		t.Fatal("want error")
	}
}
