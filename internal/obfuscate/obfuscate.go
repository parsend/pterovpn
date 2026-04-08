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
	need := len(p)
	if cap(*buf) < need {
		*buf = make([]byte, need)
	}
	b := (*buf)[:need]
	copy(b, p)
	xorBytes(b, c.key, &c.wPos)
	return c.Conn.Write(b)
}

func xorBytes(b, key []byte, pos *int) {
	kl := len(key)
	p0 := *pos
	if kl == 32 {
		for i := range b {
			b[i] ^= key[p0&31]
			p0++
		}
	} else {
		for i := range b {
			b[i] ^= key[p0%kl]
			p0++
		}
	}
	*pos = p0
}

func WrapConn(conn net.Conn, token string) net.Conn {
	return conn
}
