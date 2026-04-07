package tunnel

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net"
	"strings"

	"github.com/unitdevgcc/pterovpn/internal/clientlog"
	"github.com/unitdevgcc/pterovpn/internal/config"
	"github.com/unitdevgcc/pterovpn/internal/protocol"
)

const obfMixLabel = "pteravpn-tcp-obf-v1"

func obfMix(seed []byte, slot int64) [32]byte {
	var slotLE [8]byte
	binary.LittleEndian.PutUint64(slotLE[:], uint64(slot))
	return sha256.Sum256(append(append([]byte(obfMixLabel), seed...), slotLE[:]...))
}

var magicSplitPatterns = []string{
	"1,1,3", "1,2,2", "2,1,2", "2,2,1", "3,1,1", "1,4", "4,1", "5",
}

func pickMagicSplit(seed []byte, slot int64, configured string) string {
	if strings.TrimSpace(configured) != "" {
		return configured
	}
	if len(seed) < 8 {
		return ""
	}
	m := obfMix(seed, slot)
	return magicSplitPatterns[int(m[8])%len(magicSplitPatterns)]
}

func streamObf(prot *config.ProtectionOptions, slot int64, udpMaxPad bool, seed []byte) (maxPad, prefixLen int, junkCount, junkMin, junkMax int, junkStyle, flushPolicy string) {
	useSeed := len(seed) >= 8
	var mix [32]byte
	if useSeed {
		mix = obfMix(seed, slot)
	}

	maxPad = 32
	if udpMaxPad {
		if prot != nil && prot.PadS4 > 0 && prot.PadS4 <= 64 {
			maxPad = prot.PadS4
		}
		if useSeed {
			maxPad += int(mix[0] % 16)
		} else {
			maxPad += int(slot % 16)
		}
		if maxPad > 64 {
			maxPad = 64
		}
	}

	prefixLen = 0
	junkCount, junkMin, junkMax = 0, 64, 1024
	if prot != nil {
		prefixLen = prot.PadS1 + prot.PadS2 + prot.PadS3
		if prefixLen > 64 {
			prefixLen = 64
		}
		if useSeed {
			prefixLen += int(mix[1] % 8)
		} else {
			prefixLen += int(slot % 8)
		}
		if prefixLen > 64 {
			prefixLen = 64
		}
		if prot.JunkCount > 0 {
			junkCount = prot.JunkCount
			if prot.JunkMin > 0 {
				junkMin = prot.JunkMin
			}
			if prot.JunkMax > junkMin {
				junkMax = prot.JunkMax
			}
		}
		if strings.EqualFold(prot.Obfuscation, "enhanced") && junkCount > 0 {
			junkCount += 3
			if junkCount > 12 {
				junkCount = 12
			}
		}
		junkStyle, flushPolicy = prot.JunkStyle, prot.FlushPolicy
	}
	if junkCount == 0 {
		junkCount, junkMin, junkMax = 2, 64, 512
	}

	if useSeed {
		junkCount, junkMin, junkMax = applyMixJunkVariation(junkCount, junkMin, junkMax, mix[:])
		if strings.TrimSpace(junkStyle) == "" {
			if mix[9]%2 == 0 {
				junkStyle = "tls"
			}
		}
		if strings.TrimSpace(flushPolicy) == "" {
			if mix[10]%4 == 0 {
				flushPolicy = "perChunk"
			}
		}
	} else {
		junkCount, junkMin, junkMax = protocol.ApplyTimeVariation(junkCount, junkMin, junkMax, slot)
	}
	return
}

func applyMixJunkVariation(count, min, max int, mix []byte) (int, int, int) {
	if len(mix) < 12 {
		return count, min, max
	}
	count = count + int(mix[2]%5)
	if count < 1 {
		count = 1
	}
	if count > 16 {
		count = 16
	}
	min = min + int(mix[3]%16)*8
	if min < 64 {
		min = 64
	}
	max = max + int(mix[4]%32)*32
	if max < min {
		max = min + 256
	}
	if max > 2048 {
		max = 2048
	}
	return count, min, max
}

func handshakeOptsJSON(prot *config.ProtectionOptions, seed []byte, slot int64, negotiatedUDPMaxPad int, magicSplit string, udpChannel bool) []byte {
	enc := base64.RawURLEncoding.EncodeToString(seed)
	if prot != nil {
		wire := *prot
		wire.ObfSeed = enc
		if udpChannel && negotiatedUDPMaxPad >= 0 {
			wire.PadS4 = negotiatedUDPMaxPad
		}
		if strings.TrimSpace(wire.MagicSplit) == "" && magicSplit != "" {
			wire.MagicSplit = magicSplit
		}
		b, err := json.Marshal(wire)
		if err != nil {
			return nil
		}
		return b
	}
	type wireMinimal struct {
		ObfSeed    string `json:"obfSeed"`
		PadS4      int    `json:"padS4,omitempty"`
		MagicSplit string `json:"magicSplit,omitempty"`
	}
	wm := wireMinimal{ObfSeed: enc, MagicSplit: magicSplit}
	if udpChannel && negotiatedUDPMaxPad > 0 {
		wm.PadS4 = negotiatedUDPMaxPad
	}
	b, err := json.Marshal(wm)
	if err != nil {
		return nil
	}
	return b
}

func WriteUDPChannelPreambleSlot(w *bufio.Writer, channelID byte, token string, prot *config.ProtectionOptions, slot int64) (maxPad int, err error) {
	seed := make([]byte, 16)
	if _, err := rand.Read(seed); err != nil {
		return 0, err
	}
	ms := ""
	if prot != nil {
		ms = prot.MagicSplit
	}
	ms = pickMagicSplit(seed, slot, ms)
	maxPad, prefixLen, jc, jmin, jmax, jstyle, flush := streamObf(prot, slot, true, seed)
	if err = protocol.WriteJunkOrTLSLike(w, jc, jmin, jmax, jstyle, flush, func() { _ = w.Flush() }); err != nil {
		return 0, err
	}
	if !strings.EqualFold(flush, "perChunk") {
		_ = w.Flush()
	}
	optsJSON := handshakeOptsJSON(prot, seed, slot, maxPad, ms, true)
	if err = protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleUDP(), channelID, token, prefixLen, optsJSON, slot); err != nil {
		return 0, err
	}
	return maxPad, nil
}

func WriteUDPChannelPreamble(w *bufio.Writer, channelID byte, token string, prot *config.ProtectionOptions) (maxPad int, err error) {
	return WriteUDPChannelPreambleSlot(w, channelID, token, prot, protocol.TimeSlot())
}

func tcpRelayPreamble(w *bufio.Writer, token string, prot *config.ProtectionOptions, slot int64) error {
	seed := make([]byte, 16)
	if _, err := rand.Read(seed); err != nil {
		return err
	}
	ms := ""
	if prot != nil {
		ms = prot.MagicSplit
	}
	ms = pickMagicSplit(seed, slot, ms)
	_, prefixLen, jc, jmin, jmax, jstyle, flush := streamObf(prot, slot, false, seed)
	if err := protocol.WriteJunkOrTLSLike(w, jc, jmin, jmax, jstyle, flush, func() { _ = w.Flush() }); err != nil {
		return err
	}
	if !strings.EqualFold(flush, "perChunk") {
		_ = w.Flush()
	}
	optsJSON := handshakeOptsJSON(prot, seed, slot, 0, ms, false)
	return protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, token, prefixLen, optsJSON, slot)
}

func DialTunFlow(addrs []string, dst net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, dual bool, sel *DualPathSelector) (net.Conn, bool, bool, error) {
	preferTCP := false
	if dual && quicShared != nil && UsesQUICTransport(transport, quicServer) {
		if sel != nil {
			preferTCP = !sel.PreferQUIC()
		}
	}
	if preferTCP {
		c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		return c, false, true, err
	}
	c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, false)
	if err != nil && dual && quicShared != nil {
		if sel != nil {
			sel.RecordQuicOutcome(false)
		}
		quicErr := err
		if quicTraceOn() {
			clientlog.Trace("tun tcp quic dial failed, fallback tcp: %v", quicErr)
		}
		clientlog.Warn("vpn: tun-tcp QUIC path failed, fallback TCP: %v", quicErr)
		c, err = Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		return c, true, false, err
	}
	if err == nil && sel != nil {
		sel.RecordQuicOutcome(true)
	}
	return c, false, false, err
}
