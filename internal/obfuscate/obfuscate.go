package obfuscate

import (
	"crypto/sha256"
	"io"
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
	if len(p) == 0 {
		return 0, nil
	}

	if cap(*buf) < len(p) {
		size := len(p)
		if size < 32*1024 {
			size = 32 * 1024
		}
		*buf = make([]byte, size)
	}

	total := 0
	for len(p) > 0 {
		chunkLen := len(p)
		if chunkLen > len(*buf) {
			chunkLen = len(*buf)
		}
		b := (*buf)[:chunkLen]
		copy(b, p[:chunkLen])
		xorBytes(b, c.key, &c.wPos)
		w, e := c.Conn.Write(b)
		if w > 0 {
			total += w
			if w < chunkLen {
				c.wPos -= (chunkLen - w)
			}
			p = p[w:]
		}
		if e != nil {
			return total, e
		}
		if w == 0 {
			return total, io.ErrShortWrite
		}
	}
	return total, nil
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
