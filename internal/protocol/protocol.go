package protocol

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"net"
)

var magic = []byte{'P', 'T', 'V', 'P', 'N'}

const (
	version = 1

	roleUDP  = 1
	roleTCP  = 2
	roleQUIC = 3

	msgUDP = 1

	addrV4 = 4
	addrV6 = 6
)

type Handshake struct {
	Role      byte
	ChannelID byte
	Token     string
}

func WriteHandshake(w *bufio.Writer, role byte, channelID byte, token string) error {
	if _, err := w.Write(magic); err != nil {
		return err
	}
	if err := w.WriteByte(version); err != nil {
		return err
	}
	if err := w.WriteByte(role); err != nil {
		return err
	}
	if len(token) > 4096 {
		return errors.New("token too long")
	}
	if err := writeU16(w, uint16(len(token))); err != nil {
		return err
	}
	if _, err := w.WriteString(token); err != nil {
		return err
	}
	if role == roleUDP || role == roleQUIC {
		if err := w.WriteByte(channelID); err != nil {
			return err
		}
	}
	return w.Flush()
}

func ReadHandshake(r *bufio.Reader) (Handshake, error) {
	got := make([]byte, len(magic))
	if _, err := io.ReadFull(r, got); err != nil {
		return Handshake{}, err
	}
	for i := range magic {
		if got[i] != magic[i] {
			return Handshake{}, errors.New("bad magic")
		}
	}
	ver, err := r.ReadByte()
	if err != nil {
		return Handshake{}, err
	}
	if ver != version {
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
	if tokLen > 4096 {
		return Handshake{}, errors.New("bad token len")
	}
	tok := make([]byte, tokLen)
	if _, err := io.ReadFull(r, tok); err != nil {
		return Handshake{}, err
	}
	var ch byte
	if role == roleUDP || role == roleQUIC {
		ch, err = r.ReadByte()
		if err != nil {
			return Handshake{}, err
		}
	}
	return Handshake{Role: role, ChannelID: ch, Token: string(tok)}, nil
}

type TcpConnect struct {
	AddrType byte
	IP       net.IP
	Port     uint16
}

func WriteTcpConnect(w *bufio.Writer, ip net.IP, port uint16) error {
	at, ipb, err := normalizeIP(ip)
	if err != nil {
		return err
	}
	if err := w.WriteByte(at); err != nil {
		return err
	}
	if _, err := w.Write(ipb); err != nil {
		return err
	}
	if err := writeU16(w, port); err != nil {
		return err
	}
	return w.Flush()
}

func ReadTcpConnect(r *bufio.Reader) (TcpConnect, error) {
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

type UDPFrame struct {
	AddrType byte
	SrcPort  uint16
	DstIP    net.IP
	DstPort  uint16
	Payload  []byte
}

func ReadUDPFrame(r *bufio.Reader) (UDPFrame, error) {
	flen, err := readU32(r)
	if err != nil {
		return UDPFrame{}, err
	}
	if flen < 1 || flen > (64*1024+64) {
		return UDPFrame{}, errors.New("bad frame len")
	}
	buf := make([]byte, flen)
	if _, err := io.ReadFull(r, buf); err != nil {
		return UDPFrame{}, err
	}
	if buf[0] != msgUDP {
		return UDPFrame{}, errors.New("bad msg")
	}
	at := buf[1]
	off := 2
	if len(buf) < off+2 {
		return UDPFrame{}, errors.New("short frame")
	}
	srcPort := binary.BigEndian.Uint16(buf[off : off+2])
	off += 2
	ipLen := 4
	if at == addrV6 {
		ipLen = 16
	} else if at != addrV4 {
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

func WriteUDPFrame(w *bufio.Writer, f UDPFrame) error {
	at, ipb, err := normalizeIP(f.DstIP)
	if err != nil {
		return err
	}
	ipLen := len(ipb)
	flen := 1 + 1 + 2 + ipLen + 2 + len(f.Payload)
	if err := writeU32(w, uint32(flen)); err != nil {
		return err
	}
	if err := w.WriteByte(msgUDP); err != nil {
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

func RoleUDP() byte  { return roleUDP }
func RoleTCP() byte  { return roleTCP }
func RoleQUIC() byte { return roleQUIC }

func normalizeIP(ip net.IP) (byte, []byte, error) {
	if v4 := ip.To4(); v4 != nil {
		return addrV4, []byte(v4), nil
	}
	if v6 := ip.To16(); v6 != nil {
		return addrV6, []byte(v6), nil
	}
	return 0, nil, errors.New("bad ip")
}

func readAddr(r *bufio.Reader, at byte) ([]byte, error) {
	switch at {
	case addrV4:
		b := make([]byte, 4)
		_, err := io.ReadFull(r, b)
		return b, err
	case addrV6:
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
