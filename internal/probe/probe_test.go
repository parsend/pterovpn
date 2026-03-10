package probe

import (
	"bufio"
	"net"
	"testing"
	"time"

	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"
)

func TestPingInvalidAddr(t *testing.T) {
	_, err := Ping("invalid-host-noexist:9999", 100*time.Millisecond)
	if err == nil {
		t.Error("want error for invalid addr")
	}
}

func TestPingRefused(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp:", err)
	}
	addr := l.Addr().String()
	l.Close()

	_, err = Ping(addr, time.Second)
	if err == nil {
		t.Error("want error for refused")
	}
}

func TestPingConnect(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp:", err)
	}
	defer l.Close()
	go func() {
		c, _ := l.Accept()
		if c != nil {
			c.Close()
		}
	}()

	d, err := Ping(l.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if d <= 0 {
		t.Errorf("duration=%v", d)
	}
}

func TestProbePterovpnRefused(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp:", err)
	}
	addr := l.Addr().String()
	l.Close()

	ok, _, err := ProbePterovpn(addr, 100*time.Millisecond)
	if err == nil || ok {
		t.Errorf("want err, got ok=%v err=%v", ok, err)
	}
}

func TestProbePterovpnCloseOnBadToken(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp:", err)
	}
	defer l.Close()
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		buf := make([]byte, 4096)
		for {
			_, err := c.Read(buf)
			if err != nil {
				return
			}
		}
	}()

	ok, _, err := ProbePterovpn(l.Addr().String(), time.Second)
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if !ok {
		t.Error("want ok=true when server closes after handshake")
	}
}


func TestProbePterovpnServerRejectsBadToken(t *testing.T) {
	serverToken := "secret123"
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp:", err)
	}
	defer l.Close()
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		wrapped := obfuscate.WrapConn(c, serverToken)
		r := bufio.NewReader(wrapped)
		_, err = protocol.ReadHandshakeAfterSkip(r)
		if err != nil {
			c.Close()
			return
		}
	}()

	ok, _, err := ProbePterovpn(l.Addr().String(), time.Second)
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if !ok {
		t.Error("want ok=true: server applies XOR, rejects bad token, closes")
	}
}

