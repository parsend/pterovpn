package protocol

import (
	"bufio"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io"
	"net"
)

const (
	Version = 1

	RoleUDP = 1
	RoleTCP = 2

	TypeHandshake  = 0
	TypeTcpConnect = 1
	TypeUDPFrame   = 2

	AddrV4 = 4
	AddrV6 = 6

	MagicLen = 5
	MaxToken = 4096
	MaxFrame = 64*1024 + 64
)

func MagicFromToken(token string) []byte {
	h := sha256.Sum256([]byte("ptevpn:" + token))
	return h[:MagicLen]
}

type Message interface {
	Type() byte
	Write(w *bufio.Writer) error
}

type Handshake struct {
	Role      byte
	ChannelID byte
	Token     string
}

func (h Handshake) Type() byte { return TypeHandshake }

func (h Handshake) Write(w *bufio.Writer) error {
	magic := MagicFromToken(h.Token)
	if _, err := w.Write(magic); err != nil {
		return err
	}
	if err := w.WriteByte(TypeHandshake); err != nil {
		return err
	}
	if err := w.WriteByte(Version); err != nil {
		return err
	}
	if err := w.WriteByte(h.Role); err != nil {
		return err
	}
	if len(h.Token) > MaxToken {
		return errors.New("token too long")
	}
	if err := writeU16(w, uint16(len(h.Token))); err != nil {
		return err
	}
	if _, err := w.WriteString(h.Token); err != nil {
		return err
	}
	if h.Role == RoleUDP {
		if err := w.WriteByte(h.ChannelID); err != nil {
			return err
		}
	}
	return w.Flush()
}

type TcpConnect struct {
	AddrType byte
	IP       net.IP
	Port     uint16
}

func (c TcpConnect) Type() byte { return TypeTcpConnect }

func (c TcpConnect) Write(w *bufio.Writer) error {
	if err := w.WriteByte(TypeTcpConnect); err != nil {
		return err
	}
	at, ipb, err := normalizeIP(c.IP)
	if err != nil {
		return err
	}
	if err := w.WriteByte(at); err != nil {
		return err
	}
	if _, err := w.Write(ipb); err != nil {
		return err
	}
	if err := writeU16(w, c.Port); err != nil {
		return err
	}
	return w.Flush()
}

type UDPFrame struct {
	AddrType byte
	SrcPort  uint16
	DstIP    net.IP
	DstPort  uint16
	Payload  []byte
}

func (f UDPFrame) Type() byte { return TypeUDPFrame }

func (f UDPFrame) Write(w *bufio.Writer) error {
	at, ipb, err := normalizeIP(f.DstIP)
	if err != nil {
		return err
	}
	ipLen := len(ipb)
	flen := 1 + 1 + 2 + ipLen + 2 + len(f.Payload)
	if err := writeU32(w, uint32(flen)); err != nil {
		return err
	}
	if err := w.WriteByte(TypeUDPFrame); err != nil {
		return err
	}
	if err := w.WriteByte(at); err != nil {
		return err
	}
	if err := writeU16(w, f.SrcPort); err != nil {
		return err
	}
	if _, err := w.Write(ipb); err != nil {
		return err
	}
	if err := writeU16(w, f.DstPort); err != nil {
		return err
	}
	if _, err := w.Write(f.Payload); err != nil {
		return err
	}
	return w.Flush()
}

func ReadMessage(r *bufio.Reader, expectedMagic []byte) (Message, error) {
	got := make([]byte, MagicLen)
	if _, err := io.ReadFull(r, got); err != nil {
		return nil, err
	}
	for i := range expectedMagic {
		if got[i] != expectedMagic[i] {
			return nil, errors.New("bad magic")
		}
	}
	t, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if t != TypeHandshake {
		return nil, errors.New("expected handshake")
	}
	return readHandshakeBody(r)
}

func ReadMessageAfterHandshake(r *bufio.Reader) (Message, error) {
	t, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	switch t {
	case TypeTcpConnect:
		return readTcpConnectBody(r)
	case TypeUDPFrame:
		return readUDPFrameBody(r)
	default:
		return nil, errors.New("unknown msg type")
	}
}

func readHandshakeBody(r *bufio.Reader) (Handshake, error) {
	ver, err := r.ReadByte()
	if err != nil {
		return Handshake{}, err
	}
	if ver != Version {
		return Handshake{}, errors.New("bad version")
	}
	role, err := r.ReadByte()
	if err != nil {
		return Handshake{}, err
	}
	tokLen, err := readU16(r)
	if err != nil {
		return Handshake{}, err
	}
	if tokLen > MaxToken {
		return Handshake{}, errors.New("bad token len")
	}
	tok := make([]byte, tokLen)
	if _, err := io.ReadFull(r, tok); err != nil {
		return Handshake{}, err
	}
	var ch byte
	if role == RoleUDP {
		ch, err = r.ReadByte()
		if err != nil {
			return Handshake{}, err
		}
	}
	return Handshake{Role: role, ChannelID: ch, Token: string(tok)}, nil
}

func readTcpConnectBody(r *bufio.Reader) (TcpConnect, error) {
	at, err := r.ReadByte()
	if err != nil {
		return TcpConnect{}, err
	}
	ipb, err := readAddr(r, at)
	if err != nil {
		return TcpConnect{}, err
	}
	p, err := readU16(r)
	if err != nil {
		return TcpConnect{}, err
	}
	return TcpConnect{AddrType: at, IP: net.IP(ipb), Port: p}, nil
}

func readUDPFrameBody(r *bufio.Reader) (UDPFrame, error) {
	flen, err := readU32(r)
	if err != nil {
		return UDPFrame{}, err
	}
	if flen < 1 || flen > MaxFrame {
		return UDPFrame{}, errors.New("bad frame len")
	}
	buf := make([]byte, flen)
	if _, err := io.ReadFull(r, buf); err != nil {
		return UDPFrame{}, err
	}
	at := buf[0]
	off := 1
	if len(buf) < off+2 {
		return UDPFrame{}, errors.New("short frame")
	}
	srcPort := binary.BigEndian.Uint16(buf[off : off+2])
	off += 2
	ipLen := 4
	if at == AddrV6 {
		ipLen = 16
	} else if at != AddrV4 {
		return UDPFrame{}, errors.New("bad addr type")
	}
	if len(buf) < off+ipLen+2 {
		return UDPFrame{}, errors.New("short frame")
	}
	dstIP := net.IP(buf[off : off+ipLen])
	off += ipLen
	dstPort := binary.BigEndian.Uint16(buf[off : off+2])
	off += 2
	payload := make([]byte, len(buf)-off)
	copy(payload, buf[off:])
	return UDPFrame{AddrType: at, SrcPort: srcPort, DstIP: dstIP, DstPort: dstPort, Payload: payload}, nil
}

func normalizeIP(ip net.IP) (byte, []byte, error) {
	if v4 := ip.To4(); v4 != nil {
		return AddrV4, []byte(v4), nil
	}
	if v6 := ip.To16(); v6 != nil {
		return AddrV6, []byte(v6), nil
	}
	return 0, nil, errors.New("bad ip")
}

func readAddr(r *bufio.Reader, at byte) ([]byte, error) {
	switch at {
	case AddrV4:
		b := make([]byte, 4)
		_, err := io.ReadFull(r, b)
		return b, err
	case AddrV6:
		b := make([]byte, 16)
		_, err := io.ReadFull(r, b)
		return b, err
	default:
		return nil, errors.New("bad addr type")
	}
}

func readU16(r *bufio.Reader) (uint16, error) {
	var b [2]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint16(b[:]), nil
}

func readU32(r *bufio.Reader) (uint32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(b[:]), nil
}

func writeU16(w *bufio.Writer, v uint16) error {
	var b [2]byte
	binary.BigEndian.PutUint16(b[:], v)
	_, err := w.Write(b[:])
	return err
}

func writeU32(w *bufio.Writer, v uint32) error {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	_, err := w.Write(b[:])
	return err
}
