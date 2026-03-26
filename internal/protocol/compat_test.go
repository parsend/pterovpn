package protocol

import (
	"bufio"
	"bytes"
	"net"
	"testing"
)

func TestWireCompatHandshake(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	WriteHandshake(w, RoleTCP(), 0, "tok")
	w.Flush()
	b := buf.Bytes()
	if len(b) < 10 || string(b[:5]) != "PTVPN" {
		t.Fatalf("bad handshake header: %x", b[:5])
	}
	if b[5] != 1 || b[6] != RoleTCP() {
		t.Errorf("version/role %d %d", b[5], b[6])
	}
	if len(b) < 2 || b[len(b)-2] != 0 || b[len(b)-1] != 0 {
		t.Errorf("want trailing big-endian u16(0) opts len, got len=%d", len(b))
	}
}

func TestWireCompatUdpFrame(t *testing.T) {
	f := UDPFrame{
		AddrType: addrV4,
		SrcPort:  53,
		DstIP:    net.ParseIP("8.8.8.8"),
		DstPort:  53,
		Payload:  []byte{1, 2, 3},
	}
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	if err := WriteUDPFrame(w, f); err != nil {
		t.Fatal(err)
	}
	got, err := ReadUDPFrame(bufio.NewReader(&buf))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Payload, f.Payload) {
		t.Error("payload mismatch")
	}
}
