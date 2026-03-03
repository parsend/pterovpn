package obfuscate

import (
	"crypto/rand"
	"crypto/sha256"
	"net"
	"sync"
)

func keyFromToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

type xorConn struct {
	net.Conn
	key  []byte
	rPos int
	wPos int
}

func (c *xorConn) Read(p []byte) (n int, err error) {
	n, err = c.Conn.Read(p)
	xorBytes(p[:n], c.key, &c.rPos)
	return n, err
}

var writeBufPool = sync.Pool{
	New: func() any { b := make([]byte, 256*1024); return &b },
}

func (c *xorConn) Write(p []byte) (n int, err error) {
	buf := writeBufPool.Get().(*[]byte)
	defer writeBufPool.Put(buf)
	if cap(*buf) < len(p) {
		*buf = make([]byte, len(p)*2)
	}
	b := (*buf)[:len(p)]
	copy(b, p)
	xorBytes(b, c.key, &c.wPos)
	return c.Conn.Write(b)
}

func xorBytes(b, key []byte, pos *int) {
	for i := range b {
		b[i] ^= key[*pos%len(key)]
		*pos++
	}
}

func WrapConn(conn net.Conn, token string) net.Conn {
	return &xorConn{Conn: conn, key: keyFromToken(token)}
}

func ObfuscateUDPPacket(token string, plain []byte) ([]byte, error) {
	if len(plain) == 0 {
		return nil, nil
	}
	nonce := make([]byte, 4)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	key := udpPacketKey(token, nonce)
	out := make([]byte, 4+len(plain))
	copy(out[:4], nonce)
	for i := range plain {
		out[4+i] = plain[i] ^ key[i%len(key)]
	}
	return out, nil
}

func DeobfuscateUDPPacket(token string, cipher []byte) ([]byte, error) {
	if len(cipher) < 5 {
		return nil, nil
	}
	nonce := cipher[:4]
	key := udpPacketKey(token, nonce)
	plain := make([]byte, len(cipher)-4)
	for i := range plain {
		plain[i] = cipher[4+i] ^ key[i%len(key)]
	}
	return plain, nil
}

func udpPacketKey(token string, nonce []byte) []byte {
	h := sha256.New()
	h.Write([]byte(token))
	h.Write(nonce)
	return h.Sum(nil)
}
