package obfuscate

import (
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
