package proxy

import (
	"context"
	"encoding/binary"
	"io"
	"net"

	"github.com/parsend/pterovpn/internal/clientlog"
	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/protocol"
	"github.com/parsend/pterovpn/internal/tunnel"
)

func Run(ctx context.Context, listenAddr string, serverAddrs []string, token string, transportName string, prot *config.ProtectionOptions) error {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}
	defer ln.Close()
	clientlog.Info("proxy: listening on %s", listenAddr)

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			clientlog.Err("proxy: accept: %v", err)
			continue
		}
		go handleSOCKS5(conn, serverAddrs, token, transportName, prot)
	}
}

func handleSOCKS5(client net.Conn, serverAddrs []string, token string, transportName string, prot *config.ProtectionOptions) {
	defer client.Close()

	buf := make([]byte, 257)
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	if buf[0] != 5 {
		return
	}
	nmethods := int(buf[1])
	if nmethods > 0 {
		if _, err := io.ReadFull(client, buf[:nmethods]); err != nil {
			return
		}
	}
	if _, err := client.Write([]byte{5, 0}); err != nil {
		return
	}

	if _, err := io.ReadFull(client, buf[:4]); err != nil {
		return
	}
	if buf[0] != 5 {
		return
	}
	cmd := buf[1]
	if cmd != 1 {
		reply(client, 7)
		return
	}
	atyp := buf[3]
	var host string
	var port uint16
	switch atyp {
	case 1:
		if _, err := io.ReadFull(client, buf[:6]); err != nil {
			return
		}
		host = net.IP(buf[:4]).String()
		port = binary.BigEndian.Uint16(buf[4:6])
	case 3:
		if _, err := io.ReadFull(client, buf[:1]); err != nil {
			return
		}
		n := int(buf[0])
		if _, err := io.ReadFull(client, buf[:n+2]); err != nil {
			return
		}
		host = string(buf[:n])
		port = binary.BigEndian.Uint16(buf[n : n+2])
	case 4:
		if _, err := io.ReadFull(client, buf[:18]); err != nil {
			return
		}
		host = net.IP(buf[:16]).String()
		port = binary.BigEndian.Uint16(buf[16:18])
	default:
		reply(client, 8)
		return
	}

	ip := net.ParseIP(host)
	if ip == nil {
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			reply(client, 4)
			return
		}
		for _, a := range ips {
			if a.To4() != nil {
				ip = a
				break
			}
		}
		if ip == nil {
			ip = ips[0]
		}
	}

	remote, err := tunnel.Dial(serverAddrs, ip, port, token, transportName, prot)
	if err != nil {
		clientlog.DPI("proxy: tunnel %s:%d: %v", host, port, err)
		reply(client, 1)
		return
	}
	defer remote.Close()

	reply(client, 0)

	copyBufSize := protocol.CopyBufSize(0)
	go func() {
		cb := make([]byte, copyBufSize)
		io.CopyBuffer(remote, client, cb)
	}()
	cb := make([]byte, copyBufSize)
	io.CopyBuffer(client, remote, cb)
}

func reply(c net.Conn, rep byte) {
	c.Write([]byte{5, rep, 0, 1, 0, 0, 0, 0, 0, 0})
}
