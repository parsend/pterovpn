package obfuscate

import (
	"crypto/sha256"
	"io"
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

// WrapConn returns a net.Conn that XORs all Read/Write with key = SHA256(token).
// Caller must write magic (5 bytes) in clear before using this for handshake body and rest.
func WrapConn(conn net.Conn, token string) net.Conn {
	return &xorConn{Conn: conn, key: keyFromToken(token)}
}

// WrapAfterMagic wraps conn for use after magic was read (server) or written (client).
// Server: read 5 bytes magic from conn, then use WrapAfterMagic(conn, token) for rest.
func WrapAfterMagic(conn net.Conn, token string) net.Conn {
	return &xorConn{Conn: conn, key: keyFromToken(token)}
}

// WriteMagic writes the 5-byte magic to w (e.g. raw conn before wrap).
func WriteMagic(w io.Writer, magic []byte) error {
	_, err := w.Write(magic)
	return err
}
