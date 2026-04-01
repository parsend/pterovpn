package probe

import (
	"bufio"
	"bytes"
	"net"
	"testing"
	"time"

	"github.com/unitdevgcc/pterovpn/internal/obfuscate"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

func TestPingInvalidAddr(t *testing.T) {
	_, err := Ping("invalid-host-noexist:9999", 100*time.Millisecond)
	if err == nil {
		t.Error("want error for invalid addr")
	}
}

func TestParseProbeCapsV2(t *testing.T) {
	var wire bytes.Buffer
	_ = protocol.WriteServerHelloCaps(&wire, protocol.ServerHelloCaps{
		Version:       protocol.CapsVersion,
		LegacyIPv6:    true,
		TransportMask: protocol.TransportTCP | protocol.TransportQUIC,
		FeatureBits:   protocol.FeatureIPv6,
		QuicPort:      7443,
	})
	ok, ipv6, caps, err := parseProbeCaps(wire.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if !ok || !ipv6 || caps == nil || caps.QuicPort != 7443 {
		t.Fatalf("bad parse ok=%v ipv6=%v caps=%+v", ok, ipv6, caps)
	}
	if mode := ServerModeFromCaps(caps); mode != "quic/tcp" {
		t.Fatalf("bad mode: %s", mode)
	}
	if !RecommendDualTunTransport(caps, true) {
		t.Fatal("want dual when quic mux and both transports")
	}
	if RecommendDualTunTransport(caps, false) {
		t.Fatal("want no dual without quic mux")
	}
	if RecommendDualTunTransport(nil, true) {
		t.Fatal("want no dual without caps")
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

	ok, _, err := ProbePterovpn(addr, "unused", 100*time.Millisecond)
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

	ok, _, err := ProbePterovpn(l.Addr().String(), "any", time.Second)
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
			return
		}
		var plain bytes.Buffer
		_ = protocol.WriteServerHelloCaps(&plain, protocol.ServerHelloCaps{
			Version:       protocol.CapsVersion,
			LegacyIPv6:    false,
			TransportMask: protocol.TransportTCP,
		})
		_, _ = c.Write(plain.Bytes())
	}()

	ok, _, err := ProbePterovpn(l.Addr().String(), serverToken, time.Second)
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if !ok {
		t.Error("want ok=true: server applies XOR, rejects bad token, closes")
	}
}
