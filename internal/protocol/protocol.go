package protocol

import (
	"bufio"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	frand "math/rand/v2"
	"net"
	"strconv"
	"strings"
	"time"
)

const maxOptsLen = 512

const HelloCapsHeaderLen = 11
const HelloCapsMaxNonceLen = 32

const helloCapsExtQuicLeafPin byte = 1
const helloCapsExtQuicAlpn byte = 2

const maxQuicAlpnLen = 32

const maxPrefixLen = 64

var magic = []byte{'P', 'T', 'V', 'P', 'N'}

const (
	version = 1

	roleUDP = 1
	roleTCP = 2

	msgUDP = 1

	addrV4 = 4
	addrV6 = 6
	maxPad = 32

	CapsVersion   = 1
	TransportTCP  = 1
	TransportQUIC = 1 << 1
	FeatureIPv6   = 1
)

type ServerHelloCaps struct {
	Version       byte
	LegacyIPv6    bool
	TransportMask byte
	FeatureBits   uint16
	QuicPort      uint16
	TCPPortHint   uint16
	ObfsProfileID byte
	Nonce         []byte
	QuicLeafPinSHA256 []byte
	QuicAlpn string
}

type Handshake struct {
	Role      byte
	ChannelID byte
	Token     string
}

func SkipUntilMagic(r io.Reader) error {
	var buf [5]byte
	n := 0
	for {
		b, err := readByte(r)
		if err != nil {
			return err
		}
		buf[0], buf[1], buf[2], buf[3], buf[4] = buf[1], buf[2], buf[3], buf[4], b
		n++
		if n >= 5 && buf[0] == magic[0] && buf[1] == magic[1] && buf[2] == magic[2] && buf[3] == magic[3] && buf[4] == magic[4] {
			return nil
		}
	}
}

func readByte(r io.Reader) (byte, error) {
	var b [1]byte
	_, err := io.ReadFull(r, b[:])
	return b[0], err
}

const slotSec = 120

func TimeSlot() int64 {
	return time.Now().Unix() / slotSec
}

func BufSizeForConn(slot int64) int {
	if slot <= 0 {
		slot = TimeSlot()
	}
	return 4*1024 + int(slot%13)*1024
}

func CopyBufSize(slot int64) int {
	if slot <= 0 {
		slot = TimeSlot()
	}
	return 64*1024 + int(slot%4)*64*1024
}

func ApplyTimeVariation(count, min, max int, slot int64) (int, int, int) {
	if slot <= 0 {
		return count, min, max
	}
	s := int(slot)
	count = count + (s % 5)
	if count < 1 {
		count = 1
	}
	if count > 16 {
		count = 16
	}
	min = min + (s%16)*8
	if min < 64 {
		min = 64
	}
	max = max + (s%32)*32
	if max < min {
		max = min + 256
	}
	if max > 2048 {
		max = 2048
	}
	return count, min, max
}

func writeAllSplit(w io.Writer, p []byte, splitMax int) error {
	if splitMax > 0 && splitMax < 48 {
		splitMax = 48
	}
	if splitMax <= 0 || len(p) <= splitMax {
		_, err := w.Write(p)
		return err
	}
	off := 0
	for off < len(p) {
		seg := len(p) - off
		if seg > splitMax {
			n, _ := rand.Int(rand.Reader, big.NewInt(int64(splitMax-48)))
			seg = 48 + int(n.Int64())
			if seg > splitMax {
				seg = splitMax
			}
			if seg > len(p)-off {
				seg = len(p) - off
			}
		}
		nw, err := w.Write(p[off : off+seg])
		if err != nil {
			return err
		}
		off += nw
	}
	return nil
}

func WriteJunk(w io.Writer, count, min, max int, flushAfterChunk func(), splitMax int) error {
	if count <= 0 || min <= 0 || max < min {
		return nil
	}
	if max > 1024 {
		max = 1024
	}
	if min > max {
		min = max
	}
	buf := make([]byte, max)
	for i := 0; i < count; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(max-min+1)))
		sz := min + int(n.Int64())
		_, _ = rand.Read(buf[:sz])
		var err error
		if splitMax > 0 {
			err = writeAllSplit(w, buf[:sz], splitMax)
		} else {
			_, err = w.Write(buf[:sz])
		}
		if err != nil {
			return err
		}
		if flushAfterChunk != nil {
			flushAfterChunk()
		}
	}
	return nil
}

func WriteJunkOrTLSLike(w io.Writer, count, min, max int, junkStyle, flushPolicy string, flush func(), splitMax int) error {
	var fc func()
	if flush != nil && strings.EqualFold(flushPolicy, "perChunk") {
		fc = flush
	}
	if strings.EqualFold(junkStyle, "tls") {
		return WriteTLSLikeJunk(w, count, min, max, fc, splitMax)
	}
	return WriteJunk(w, count, min, max, fc, splitMax)
}

func WriteTLSLikeJunk(w io.Writer, count, minLen, maxLen int, flushAfterChunk func(), splitMax int) error {
	if count <= 0 || minLen <= 0 || maxLen < minLen {
		return nil
	}
	if maxLen > 1024 {
		maxLen = 1024
	}
	if minLen > maxLen {
		minLen = maxLen
	}
	payload := make([]byte, maxLen)
	for i := 0; i < count; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(maxLen-minLen+1)))
		payloadLen := minLen + int(n.Int64())
		header := [5]byte{0x16, 0x03, 0x01, byte(payloadLen >> 8), byte(payloadLen)}
		if _, err := rand.Read(payload[:payloadLen]); err != nil {
			return err
		}
		record := make([]byte, 5+payloadLen)
		copy(record[0:5], header[:])
		copy(record[5:], payload[:payloadLen])
		var err error
		if splitMax > 0 {
			err = writeAllSplit(w, record, splitMax)
		} else {
			_, err = w.Write(record)
		}
		if err != nil {
			return err
		}
		if flushAfterChunk != nil {
			flushAfterChunk()
		}
	}
	return nil
}

func WriteHandshake(w *bufio.Writer, role byte, channelID byte, token string) error {
	return WriteHandshakeWithPrefix(w, role, channelID, token, 0)
}

func WriteServerHelloCaps(w io.Writer, caps ServerHelloCaps) error {
	legacy := byte(0)
	if caps.LegacyIPv6 {
		legacy = 1
	}
	nonceLen := len(caps.Nonce)
	if nonceLen > HelloCapsMaxNonceLen {
		return errors.New("caps nonce too long")
	}
	payload := make([]byte, 0, 11+nonceLen)
	payload = append(payload, caps.Version)
	payload = append(payload, legacy)
	payload = append(payload, caps.TransportMask)
	var b2 [2]byte
	binary.BigEndian.PutUint16(b2[:], caps.FeatureBits)
	payload = append(payload, b2[:]...)
	binary.BigEndian.PutUint16(b2[:], caps.QuicPort)
	payload = append(payload, b2[:]...)
	binary.BigEndian.PutUint16(b2[:], caps.TCPPortHint)
	payload = append(payload, b2[:]...)
	payload = append(payload, caps.ObfsProfileID)
	payload = append(payload, byte(nonceLen))
	payload = append(payload, caps.Nonce...)
	if _, err := w.Write(payload); err != nil {
		return err
	}
	if len(caps.QuicLeafPinSHA256) != 0 {
		if len(caps.QuicLeafPinSHA256) != 32 {
			return fmt.Errorf("caps quic leaf pin must be 32 bytes")
		}
		if _, err := w.Write([]byte{helloCapsExtQuicLeafPin}); err != nil {
			return err
		}
		if _, err := w.Write(caps.QuicLeafPinSHA256); err != nil {
			return err
		}
	}
	alpn := strings.TrimSpace(caps.QuicAlpn)
	if alpn != "" {
		if len(alpn) > maxQuicAlpnLen {
			return errors.New("caps quic alpn too long")
		}
		if _, err := w.Write([]byte{helloCapsExtQuicAlpn}); err != nil {
			return err
		}
		if _, err := w.Write([]byte{byte(len(alpn))}); err != nil {
			return err
		}
		if _, err := w.Write([]byte(alpn)); err != nil {
			return err
		}
	}
	return nil
}

func ReadServerHelloCaps(r io.Reader) (ServerHelloCaps, error) {
	buf := make([]byte, 11)
	if _, err := io.ReadFull(r, buf); err != nil {
		return ServerHelloCaps{}, err
	}
	nonceLen := int(buf[10])
	if nonceLen > HelloCapsMaxNonceLen {
		return ServerHelloCaps{}, errors.New("caps nonce too long")
	}
	nonce := make([]byte, nonceLen)
	if nonceLen > 0 {
		if _, err := io.ReadFull(r, nonce); err != nil {
			return ServerHelloCaps{}, err
		}
	}
	caps := ServerHelloCaps{
		Version:       buf[0],
		LegacyIPv6:    buf[1] == 1,
		TransportMask: buf[2],
		FeatureBits:   binary.BigEndian.Uint16(buf[3:5]),
		QuicPort:      binary.BigEndian.Uint16(buf[5:7]),
		TCPPortHint:   binary.BigEndian.Uint16(buf[7:9]),
		ObfsProfileID: buf[9],
		Nonce:         nonce,
	}
	for {
		var tag [1]byte
		_, err := io.ReadFull(r, tag[:])
		if err == io.EOF {
			return caps, nil
		}
		if err != nil {
			return caps, err
		}
		switch tag[0] {
		case helloCapsExtQuicLeafPin:
			pin := make([]byte, 32)
			if _, err := io.ReadFull(r, pin); err != nil {
				return ServerHelloCaps{}, err
			}
			caps.QuicLeafPinSHA256 = pin
		case helloCapsExtQuicAlpn:
			var ln [1]byte
			if _, err := io.ReadFull(r, ln[:]); err != nil {
				return ServerHelloCaps{}, err
			}
			L := int(ln[0])
			if L < 1 || L > maxQuicAlpnLen {
				return ServerHelloCaps{}, errors.New("caps quic alpn len")
			}
			alpn := make([]byte, L)
			if _, err := io.ReadFull(r, alpn); err != nil {
				return ServerHelloCaps{}, err
			}
			caps.QuicAlpn = string(alpn)
		default:
			return ServerHelloCaps{}, fmt.Errorf("caps unknown extension tag %02x", tag[0])
		}
	}
}

func WriteHandshakeWithPrefix(w *bufio.Writer, role byte, channelID byte, token string, prefixLen int) error {
	return WriteHandshakeWithPrefixAndOpts(w, role, channelID, token, prefixLen, nil)
}

type handshakeOpts struct {
	MagicSplit string `json:"magicSplit,omitempty"`
}

func parseMagicSplit(s string) []int {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	var lens []int
	sum := 0
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		n, err := strconv.Atoi(p)
		if err != nil || n <= 0 {
			return nil
		}
		lens = append(lens, n)
		sum += n
	}
	if sum != 5 {
		return nil
	}
	return lens
}

func WriteHandshakeWithPrefixAndOpts(w *bufio.Writer, role byte, channelID byte, token string, prefixLen int, optsJSON []byte) error {
	return WriteHandshakeWithPrefixAndOptsSlot(w, role, channelID, token, prefixLen, optsJSON, 0)
}

func WriteHandshakeWithPrefixAndOptsSlot(w *bufio.Writer, role byte, channelID byte, token string, prefixLen int, optsJSON []byte, slot int64) error {
	if prefixLen > maxPrefixLen {
		prefixLen = maxPrefixLen
	}
	if prefixLen > 0 {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(prefixLen)+1))
		pad := int(n.Int64())
		if pad > 0 {
			b := make([]byte, pad)
			_, _ = rand.Read(b)
			if _, err := w.Write(b); err != nil {
				return err
			}
		}
	}
	var opts handshakeOpts
	if len(optsJSON) > 0 && len(optsJSON) <= maxOptsLen {
		_ = json.Unmarshal(optsJSON, &opts)
	}
	splits := parseMagicSplit(opts.MagicSplit)
	if len(splits) > 0 {
		off := 0
		for _, n := range splits {
			if off+n > len(magic) {
				break
			}
			if _, err := w.Write(magic[off : off+n]); err != nil {
				return err
			}
			if err := w.Flush(); err != nil {
				return err
			}
			off += n
		}
		if off < len(magic) {
			if _, err := w.Write(magic[off:]); err != nil {
				return err
			}
		}
	} else {
		if _, err := w.Write(magic); err != nil {
			return err
		}
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
	if role == roleUDP {
		if err := w.WriteByte(channelID); err != nil {
			return err
		}
	}
	optsLen := 0
	if len(optsJSON) > 0 && len(optsJSON) <= maxOptsLen {
		optsLen = len(optsJSON)
	}
	if err := writeU16(w, uint16(optsLen)); err != nil {
		return err
	}
	if optsLen > 0 {
		if _, err := w.Write(optsJSON); err != nil {
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
	hs, err := readHandshakeBody(r)
	if err != nil {
		return Handshake{}, err
	}
	if err := discardHandshakeOpts(r); err != nil {
		return Handshake{}, err
	}
	return hs, nil
}

func ReadHandshakeAfterSkip(r *bufio.Reader) (Handshake, error) {
	if err := SkipUntilMagic(r); err != nil {
		return Handshake{}, err
	}
	hs, err := readHandshakeBody(r)
	if err != nil {
		return Handshake{}, err
	}
	if err := discardHandshakeOpts(r); err != nil {
		return Handshake{}, err
	}
	return hs, nil
}

func discardHandshakeOpts(r *bufio.Reader) error {
	n, err := readU16(r)
	if err != nil {
		return err
	}
	if n > maxOptsLen {
		return errors.New("bad opts len")
	}
	if n == 0 {
		return nil
	}
	_, err = io.CopyN(io.Discard, r, int64(n))
	return err
}

func readHandshakeBody(r *bufio.Reader) (Handshake, error) {
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
	if role == roleUDP {
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
	if flen < 2 || flen > (64*1024+64) {
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
	dstIP := make(net.IP, ipLen)
	copy(dstIP, buf[off:off+ipLen])
	off += ipLen
	dstPort := binary.BigEndian.Uint16(buf[off : off+2])
	off += 2
	padLen := int(buf[len(buf)-1] & 0xff)
	if padLen > 64 || len(buf)-1-padLen < off {
		return UDPFrame{}, errors.New("bad pad len")
	}
	payEnd := len(buf) - 1 - padLen
	payload := make([]byte, payEnd-off)
	copy(payload, buf[off:payEnd])
	return UDPFrame{AddrType: at, SrcPort: srcPort, DstIP: dstIP, DstPort: dstPort, Payload: payload}, nil
}

func WriteUDPFrame(w *bufio.Writer, f UDPFrame) error {
	return WriteUDPFrameWithPad(w, f, maxPad)
}

func WriteUDPFrameWithPad(w *bufio.Writer, f UDPFrame, maxPadVal int) error {
	if maxPadVal <= 0 || maxPadVal > 64 {
		maxPadVal = maxPad
	}
	at, ipb, err := normalizeIP(f.DstIP)
	if err != nil {
		return err
	}
	padLen := randPadLenN(maxPadVal)
	ipLen := len(ipb)
	flen := 1 + 1 + 2 + ipLen + 2 + len(f.Payload) + padLen + 1
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
	if padLen > 0 {
		var pad [64]byte
		fillUdpPadFast(pad[:padLen])
		if _, err := w.Write(pad[:padLen]); err != nil {
			return err
		}
	}
	if err := w.WriteByte(byte(padLen)); err != nil {
		return err
	}
	return w.Flush()
}

func randPadLen() int {
	return randPadLenN(maxPad)
}

func randPadLenN(m int) int {
	if m <= 0 {
		return 0
	}
	return frand.IntN(m + 1)
}

func fillUdpPadFast(p []byte) {
	for len(p) >= 8 {
		binary.LittleEndian.PutUint64(p, frand.Uint64())
		p = p[8:]
	}
	if len(p) == 0 {
		return
	}
	u := frand.Uint64()
	for i := range p {
		p[i] = byte(u)
		u >>= 8
	}
}

func RoleUDP() byte { return roleUDP }
func RoleTCP() byte { return roleTCP }

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
