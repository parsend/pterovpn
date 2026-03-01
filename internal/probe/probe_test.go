package probe

import (
	"net"
	"testing"
	"time"
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

	ok, err := ProbePterovpn(addr, 100*time.Millisecond)
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
		buf := make([]byte, 1024)
		_, _ = c.Read(buf)
		c.Close()
	}()

	ok, err := ProbePterovpn(l.Addr().String(), time.Second)
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if !ok {
		t.Error("want ok=true when server closes after handshake")
	}
}

