package obfuscate

import (
	"crypto/sha256"
	"net"
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

func (c *xorConn) Write(p []byte) (n int, err error) {
	buf := make([]byte, len(p))
	copy(buf, p)
	xorBytes(buf, c.key, &c.wPos)
	return c.Conn.Write(buf)
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
