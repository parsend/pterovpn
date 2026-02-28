package obfuscate

import (
	"bytes"
	"io"
	"net"
	"testing"
)

func TestWrapRoundtrip(t *testing.T) {
	tok := "secret123"
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	w1 := WrapConn(c1, tok)
	w2 := WrapConn(c2, tok)

	msg := []byte("hello vpn traffic")
	go func() {
		_, _ = w2.Write(msg)
		_ = w2.Close()
	}()
	buf := make([]byte, 64)
	n, err := io.ReadFull(w1, buf[:len(msg)])
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(buf[:n], msg) {
		t.Errorf("got %q", buf[:n])
	}
}

func TestWrapBidirectional(t *testing.T) {
	tok := "x"
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	w1 := WrapConn(c1, tok)
	w2 := WrapConn(c2, tok)

	done := make(chan bool)
	go func() {
		_, _ = w1.Write([]byte("ping"))
		buf := make([]byte, 4)
		_, _ = io.ReadFull(w1, buf)
		done <- bytes.Equal(buf, []byte("pong"))
	}()
	buf := make([]byte, 4)
	if _, err := io.ReadFull(w2, buf); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(buf, []byte("ping")) {
		t.Errorf("got %q", buf)
	}
	_, _ = w2.Write([]byte("pong"))
	if !<-done {
		t.Error("reverse dir failed")
	}
}

func TestKeyFromToken(t *testing.T) {
	k1 := keyFromToken("a")
	k2 := keyFromToken("a")
	if !bytes.Equal(k1, k2) {
		t.Error("same token should give same key")
	}
	if bytes.Equal(keyFromToken("a"), keyFromToken("b")) {
		t.Error("diff tokens should give diff keys")
	}
	if len(k1) != 32 {
		t.Errorf("key len %d", len(k1))
	}
}
