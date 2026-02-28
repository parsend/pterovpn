package vpn

import (
	"bufio"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"
)

func TestObfuscatedHandshakeOverTCP(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp: ", err)
	}
	defer l.Close()

	tok := "test-token"
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		c = obfuscate.WrapConn(c, tok)
		r := bufio.NewReader(c)
		hs, err := protocol.ReadHandshake(r)
		if err != nil {
			t.Errorf("server read: %v", err)
			return
		}
		if hs.Token != tok || hs.Role != protocol.RoleTCP() {
			t.Errorf("server: bad hs %+v", hs)
		}
	}()

	c, err := net.DialTimeout("tcp", l.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c = obfuscate.WrapConn(c, tok)
	w := bufio.NewWriter(c)
	if err := protocol.WriteHandshake(w, protocol.RoleTCP(), 0, tok); err != nil {
		t.Fatal(err)
	}

	wg.Wait()
}

func TestObfuscatedUDPFrameOverTCP(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no tcp: ", err)
	}
	defer l.Close()

	tok := "x"
	done := make(chan struct{})
	go func() {
		c, _ := l.Accept()
		defer c.Close()
		c = obfuscate.WrapConn(c, tok)
		r := bufio.NewReaderSize(c, 64*1024)
		f, err := protocol.ReadUDPFrame(r)
		if err != nil {
			t.Errorf("read: %v", err)
			close(done)
			return
		}
		if string(f.Payload) != "ping" {
			t.Errorf("got %q", f.Payload)
		}
		close(done)
	}()

	c, err := net.DialTimeout("tcp", l.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c = obfuscate.WrapConn(c, tok)
	w := bufio.NewWriterSize(c, 64*1024)
	f := protocol.UDPFrame{AddrType: 4, SrcPort: 1, DstIP: net.ParseIP("1.2.3.4"), DstPort: 5, Payload: []byte("ping")}
	if err := protocol.WriteUDPFrame(w, f); err != nil {
		t.Fatal(err)
	}

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout")
	}
}
