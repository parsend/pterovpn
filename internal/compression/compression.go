package compression

import (
	"compress/gzip"
	"io"
	"net"
	"time"
)

type gzipConn struct {
	conn net.Conn
	r    *gzip.Reader
	w    *gzip.Writer
}

func WrapConn(conn net.Conn) (net.Conn, error) {
	r, err := gzip.NewReader(conn)
	if err != nil {
		return nil, err
	}
	return &gzipConn{
		conn: conn,
		r:    r,
		w:    gzip.NewWriter(conn),
	}, nil
}

func (c *gzipConn) Read(p []byte) (n int, err error) {
	return c.r.Read(p)
}

func (c *gzipConn) Write(p []byte) (n int, err error) {
	return c.w.Write(p)
}

func (c *gzipConn) Close() error {
	_ = c.w.Close()
	_ = c.r.Close()
	return c.conn.Close()
}

func (c *gzipConn) LocalAddr() net.Addr  { return c.conn.LocalAddr() }
func (c *gzipConn) RemoteAddr() net.Addr { return c.conn.RemoteAddr() }

func (c *gzipConn) SetDeadline(t time.Time) error      { return c.conn.SetDeadline(t) }
func (c *gzipConn) SetReadDeadline(t time.Time) error { return c.conn.SetReadDeadline(t) }
func (c *gzipConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }

var _ net.Conn = (*gzipConn)(nil)
var _ io.Closer = (*gzipConn)(nil)
