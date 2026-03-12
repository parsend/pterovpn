package protocol

import (
	"bufio"
	"bytes"
	"net"
	"testing"
)

func TestHandshakeRoundtrip(t *testing.T) {
	for _, tc := range []struct {
		role byte
		ch   byte
		tok  string
	}{
		{RoleTCP(), 0, "x"},
		{RoleUDP(), 3, "secret"},
		{RoleUDP(), 0, ""},
	} {
		var buf bytes.Buffer
		w := bufio.NewWriter(&buf)
		if err := WriteHandshake(w, tc.role, tc.ch, tc.tok); err != nil {
			t.Fatalf("write: %v", err)
		}
		r := bufio.NewReader(&buf)
		hs, err := ReadHandshake(r)
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if hs.Role != tc.role || hs.ChannelID != tc.ch || hs.Token != tc.tok {
			t.Errorf("got role=%d ch=%d tok=%q", hs.Role, hs.ChannelID, hs.Token)
		}
	}
}

func TestHandshakeBadMagic(t *testing.T) {
	b := []byte{'X', 'X', 'X', 'X', 'X', 1, RoleTCP(), 0, 1, 'x'}
	r := bufio.NewReader(bytes.NewReader(b))
	_, err := ReadHandshake(r)
	if err == nil || err.Error() != "bad magic" {
		t.Errorf("want bad magic, got %v", err)
	}
}

func TestHandshakeBadVersion(t *testing.T) {
	b := append([]byte("PTVPN"), 99, RoleTCP(), 0, 1, 'x')
	r := bufio.NewReader(bytes.NewReader(b))
	_, err := ReadHandshake(r)
	if err == nil || err.Error() != "bad version" {
		t.Errorf("want bad version, got %v", err)
	}
}

func TestTcpConnectRoundtrip(t *testing.T) {
	for _, ip := range []string{"1.2.3.4", "::1"} {
		addr := net.ParseIP(ip)
		port := uint16(443)
		var buf bytes.Buffer
		w := bufio.NewWriter(&buf)
		if err := WriteTcpConnect(w, addr, port); err != nil {
			t.Fatalf("write %s: %v", ip, err)
		}
		r := bufio.NewReader(&buf)
		c, err := ReadTcpConnect(r)
		if err != nil {
			t.Fatalf("read %s: %v", ip, err)
		}
		if !c.IP.Equal(addr) || c.Port != port {
			t.Errorf("got %s:%d", c.IP, c.Port)
		}
	}
}

func TestUdpFrameRoundtrip(t *testing.T) {
	payloads := [][]byte{
		{},
		{0xde, 0xad, 0xbe, 0xef},
		make([]byte, 1500),
	}
	for _, p := range payloads {
		f := UDPFrame{
			AddrType: addrV4,
			SrcPort:  12345,
			DstIP:    net.ParseIP("8.8.8.8"),
			DstPort:  53,
			Payload:  p,
		}
		var buf bytes.Buffer
		w := bufio.NewWriter(&buf)
		if err := WriteUDPFrame(w, f); err != nil {
			t.Fatalf("write: %v", err)
		}
		r := bufio.NewReader(&buf)
		got, err := ReadUDPFrame(r)
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if got.SrcPort != f.SrcPort || got.DstPort != f.DstPort || !got.DstIP.Equal(f.DstIP) {
			t.Errorf("meta mismatch")
		}
		if !bytes.Equal(got.Payload, p) {
			t.Errorf("payload mismatch: len %d vs %d", len(got.Payload), len(p))
		}
	}
}

func TestUdpFrameIPv6(t *testing.T) {
	f := UDPFrame{
		AddrType: addrV6,
		SrcPort:  0,
		DstIP:    net.ParseIP("::1"),
		DstPort:  5353,
		Payload:  []byte{1, 2, 3},
	}
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	if err := WriteUDPFrame(w, f); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(&buf)
	got, err := ReadUDPFrame(r)
	if err != nil {
		t.Fatal(err)
	}
	if !got.DstIP.Equal(net.ParseIP("::1")) || !bytes.Equal(got.Payload, []byte{1, 2, 3}) {
		t.Error("ipv6 roundtrip failed")
	}
}

func TestWriteHandshakeTokenTooLong(t *testing.T) {
	tok := string(make([]byte, 5000))
	err := WriteHandshake(bufio.NewWriter(&bytes.Buffer{}), RoleTCP(), 0, tok)
	if err == nil {
		t.Error("want error for long token")
	}
}

func TestWriteJunk(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteJunk(&buf, 3, 4, 8, nil); err != nil {
		t.Fatal(err)
	}
	if buf.Len() < 12 {
		t.Errorf("buf len=%d", buf.Len())
	}
}

func TestWriteJunkZero(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteJunk(&buf, 0, 4, 8, nil); err != nil {
		t.Fatal(err)
	}
	if buf.Len() != 0 {
		t.Errorf("buf len=%d", buf.Len())
	}
}

func TestTimeSlot(t *testing.T) {
	s := TimeSlot()
	if s <= 0 {
		t.Errorf("slot=%d", s)
	}
}

func TestApplyTimeVariation(t *testing.T) {
	c, mn, mx := ApplyTimeVariation(3, 64, 512, 0)
	if c != 3 || mn != 64 || mx != 512 {
		t.Errorf("slot=0 should pass through: %d %d %d", c, mn, mx)
	}
	c, mn, mx = ApplyTimeVariation(3, 64, 512, 1)
	if c == 3 && mn == 64 && mx == 512 {
		t.Errorf("slot>0 should vary: %d %d %d", c, mn, mx)
	}
	if c < 1 || c > 16 || mn < 64 || mx < mn {
		t.Errorf("bounds violated: %d %d %d", c, mn, mx)
	}
}

func TestWriteJunkWithSlot(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteJunk(&buf, 2, 64, 256, nil); err != nil {
		t.Fatal(err)
	}
	if buf.Len() < 128 {
		t.Errorf("buf len=%d", buf.Len())
	}
}

func TestJunkThenHandshake(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	_ = WriteJunk(w, 2, 64, 256, nil)
	_ = WriteHandshake(w, RoleTCP(), 0, "t")
	r := bufio.NewReader(&buf)
	if err := SkipUntilMagic(r); err != nil {
		t.Fatal(err)
	}
	hs, err := readHandshakeBody(r)
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "t" || hs.Role != RoleTCP() {
		t.Errorf("got %+v", hs)
	}
}

func TestSkipUntilMagic(t *testing.T) {
	pad := []byte{0x00, 0x01, 0x02, 0x03}
	body := append(magic, version, RoleTCP(), 0, 1, 'x')
	r := bytes.NewReader(append(pad, body...))
	if err := SkipUntilMagic(r); err != nil {
		t.Fatal(err)
	}
	ver, err := r.ReadByte()
	if err != nil {
		t.Fatal(err)
	}
	if ver != version {
		t.Errorf("ver=%d", ver)
	}
}

func TestWriteHandshakeWithPrefix(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	if err := WriteHandshakeWithPrefix(w, RoleUDP(), 0, "x", 16); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(&buf)
	if err := SkipUntilMagic(r); err != nil {
		t.Fatal(err)
	}
	hs, err := readHandshakeBody(r)
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "x" || hs.Role != RoleUDP() {
		t.Errorf("got %+v", hs)
	}
}

func TestWriteHandshakeWithOpts(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	opts := []byte(`{"padS4":32}`)
	if err := WriteHandshakeWithPrefixAndOpts(w, RoleUDP(), 1, "t", 0, opts); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(&buf)
	hs, err := ReadHandshake(r)
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "t" || hs.ChannelID != 1 {
		t.Errorf("got %+v", hs)
	}
}

func TestWriteHandshakeSupportsTransportFieldsInOpts(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	opts := []byte(`{"transport":"tls","tlsName":"vpn.example.com","padS4":24}`)
	if err := WriteHandshakeWithPrefixAndOpts(w, RoleTCP(), 0, "x", 0, opts); err != nil {
		t.Fatal(err)
	}
	if err := w.Flush(); err != nil {
		t.Fatal(err)
	}
	hs, err := ReadHandshake(bufio.NewReader(&buf))
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "x" || hs.Role != RoleTCP() {
		t.Errorf("got %+v", hs)
	}
}

func TestBufSizeForConn(t *testing.T) {
	s := BufSizeForConn(0)
	if s < 4*1024 || s > 16*1024 {
		t.Errorf("BufSizeForConn(0)=%d", s)
	}
}

func TestCopyBufSize(t *testing.T) {
	s := CopyBufSize(0)
	if s < 64*1024 || s > 256*1024 {
		t.Errorf("CopyBufSize(0)=%d", s)
	}
}

func TestMagicSplit(t *testing.T) {
	for _, tc := range []struct {
		split string
		valid bool
	}{
		{"2,3", true},
		{"1,2,2", true},
		{"5", true},
		{"0", false},
		{"1,4", true},
		{"3,2", true},
		{"1,1,1,1,1", true},
		{"2,2", false},
		{"6", false},
	} {
		splits := parseMagicSplit(tc.split)
		valid := len(splits) > 0
		if valid != tc.valid {
			t.Errorf("split %q: valid=%v want %v, splits=%v", tc.split, valid, tc.valid, splits)
		}
	}
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	opts := []byte(`{"magicSplit":"2,3"}`)
	if err := WriteHandshakeWithPrefixAndOpts(w, RoleTCP(), 0, "x", 0, opts); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(&buf)
	if err := SkipUntilMagic(r); err != nil {
		t.Fatal(err)
	}
	hs, err := readHandshakeBody(r)
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "x" {
		t.Errorf("got token %q", hs.Token)
	}
}

func TestTLSLikeJunk(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteTLSLikeJunk(&buf, 2, 10, 50, nil); err != nil {
		t.Fatal(err)
	}
	b := buf.Bytes()
	if len(b) < 2*5+2*10 {
		t.Errorf("too short: %d", len(b))
	}
	i := 0
	for j := 0; j < 2; j++ {
		if b[i] != 0x16 || b[i+1] != 0x03 || b[i+2] != 0x01 {
			t.Errorf("chunk %d: bad TLS header: %02x %02x %02x", j, b[i], b[i+1], b[i+2])
		}
		plen := int(b[i+3])<<8 | int(b[i+4])
		if plen < 10 || plen > 50 {
			t.Errorf("chunk %d: payload len %d", j, plen)
		}
		i += 5 + plen
	}
}

func TestJunkThenHandshakeWithTLSJunk(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	if err := WriteTLSLikeJunk(w, 1, 20, 30, nil); err != nil {
		t.Fatal(err)
	}
	_ = w.Flush()
	if err := WriteHandshakeWithPrefixAndOpts(w, RoleUDP(), 0, "tls", 0, nil); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(&buf)
	if err := SkipUntilMagic(r); err != nil {
		t.Fatal(err)
	}
	hs, err := readHandshakeBody(r)
	if err != nil {
		t.Fatal(err)
	}
	if hs.Token != "tls" {
		t.Errorf("got token %q", hs.Token)
	}
}
