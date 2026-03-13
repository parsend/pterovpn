package transport

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/parsend/pterovpn/internal/config"
	"github.com/parsend/pterovpn/internal/obfuscate"
	"github.com/parsend/pterovpn/internal/protocol"
)

const bootstrapTimeout = 10 * time.Second

type ClientBundle struct {
	ServerCertPEM []byte
	ClientCertPEM []byte
	ClientKeyPEM  []byte
}

func bundleKey(serverAddr, token string) string {
	sum := sha256.Sum256([]byte(serverAddr + "|" + token))
	return base64.RawURLEncoding.EncodeToString(sum[:12])
}

func bundleDir(serverAddr, token string) (string, error) {
	dir, err := config.Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "mtls", bundleKey(serverAddr, token)), nil
}

func bundlePaths(serverAddr, token string) (string, string, string, error) {
	dir, err := bundleDir(serverAddr, token)
	if err != nil {
		return "", "", "", err
	}
	return filepath.Join(dir, "server.pem"), filepath.Join(dir, "client.pem"), filepath.Join(dir, "client-key.pem"), nil
}

func LoadClientBundle(serverAddr, token string) (ClientBundle, error) {
	serverPath, clientPath, keyPath, err := bundlePaths(serverAddr, token)
	if err != nil {
		return ClientBundle{}, err
	}
	serverPEM, err := os.ReadFile(serverPath)
	if err != nil {
		return ClientBundle{}, err
	}
	clientPEM, err := os.ReadFile(clientPath)
	if err != nil {
		return ClientBundle{}, err
	}
	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return ClientBundle{}, err
	}
	return ClientBundle{
		ServerCertPEM: serverPEM,
		ClientCertPEM: clientPEM,
		ClientKeyPEM:  keyPEM,
	}, nil
}

func SaveClientBundle(serverAddr, token string, bundle ClientBundle) error {
	dir, err := bundleDir(serverAddr, token)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	serverPath, clientPath, keyPath, err := bundlePaths(serverAddr, token)
	if err != nil {
		return err
	}
	if err := os.WriteFile(serverPath, bundle.ServerCertPEM, 0600); err != nil {
		return err
	}
	if err := os.WriteFile(clientPath, bundle.ClientCertPEM, 0600); err != nil {
		return err
	}
	if err := os.WriteFile(keyPath, bundle.ClientKeyPEM, 0600); err != nil {
		return err
	}
	return nil
}

func EnsureClientBundle(serverAddr, token string) (ClientBundle, error) {
	bundle, err := LoadClientBundle(serverAddr, token)
	if err == nil {
		return bundle, nil
	}
	if !os.IsNotExist(err) {
		return ClientBundle{}, err
	}
	bundle, err = BootstrapClientBundle(serverAddr, token)
	if err != nil {
		return ClientBundle{}, err
	}
	if err := SaveClientBundle(serverAddr, token, bundle); err != nil {
		return ClientBundle{}, err
	}
	return bundle, nil
}

func RemoveClientBundle(serverAddr, token string) error {
	dir, err := bundleDir(serverAddr, token)
	if err != nil {
		return err
	}
	if err := os.RemoveAll(dir); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func BootstrapClientBundle(serverAddr, token string) (ClientBundle, error) {
	c, err := net.DialTimeout("tcp", serverAddr, bootstrapTimeout)
	if err != nil {
		return ClientBundle{}, err
	}
	defer c.Close()
	if err := c.SetDeadline(time.Now().Add(bootstrapTimeout)); err != nil {
		return ClientBundle{}, err
	}
	if err := WritePreface(c, NameXOR); err != nil {
		return ClientBundle{}, err
	}
	wrapped := obfuscate.WrapConn(c, token)
	w := bufio.NewWriter(wrapped)
	if err := protocol.WriteHandshake(w, protocol.RoleBootstrap(), 0, token); err != nil {
		return ClientBundle{}, err
	}
	if err := w.Flush(); err != nil {
		return ClientBundle{}, err
	}
	return readBootstrapBundle(wrapped)
}

func ClientTLSConfig(serverAddr, token string) (*tls.Config, error) {
	bundle, err := EnsureClientBundle(serverAddr, token)
	if err != nil {
		return nil, err
	}
	pair, err := tls.X509KeyPair(bundle.ClientCertPEM, bundle.ClientKeyPEM)
	if err != nil {
		return nil, err
	}
	expectedServer := bytes.TrimSpace(bundle.ServerCertPEM)
	return &tls.Config{
		MinVersion:         tls.VersionTLS12,
		Certificates:       []tls.Certificate{pair},
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("mtls: missing server cert")
			}
			got := pemEncodeCert(rawCerts[0])
			if !bytes.Equal(bytes.TrimSpace(got), expectedServer) {
				return errors.New("mtls: unexpected server cert")
			}
			return nil
		},
	}, nil
}

func readBootstrapBundle(r io.Reader) (ClientBundle, error) {
	serverPEM, err := readBlob(r)
	if err != nil {
		return ClientBundle{}, err
	}
	clientPEM, err := readBlob(r)
	if err != nil {
		return ClientBundle{}, err
	}
	clientKeyPEM, err := readBlob(r)
	if err != nil {
		return ClientBundle{}, err
	}
	return ClientBundle{
		ServerCertPEM: serverPEM,
		ClientCertPEM: clientPEM,
		ClientKeyPEM:  clientKeyPEM,
	}, nil
}

func WriteBootstrapBundle(w io.Writer, bundle ClientBundle) error {
	if err := writeBlob(w, bundle.ServerCertPEM); err != nil {
		return err
	}
	if err := writeBlob(w, bundle.ClientCertPEM); err != nil {
		return err
	}
	return writeBlob(w, bundle.ClientKeyPEM)
}

func writeBlob(w io.Writer, b []byte) error {
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(b)))
	if _, err := w.Write(lenBuf[:]); err != nil {
		return err
	}
	if len(b) == 0 {
		return nil
	}
	_, err := w.Write(b)
	return err
}

func readBlob(r io.Reader) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(lenBuf[:])
	if n == 0 {
		return nil, nil
	}
	if n > 1<<20 {
		return nil, fmt.Errorf("mtls: blob too large")
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func pemEncodeCert(der []byte) []byte {
	var b bytes.Buffer
	b.WriteString("-----BEGIN CERTIFICATE-----\n")
	enc := base64.StdEncoding.EncodeToString(der)
	for len(enc) > 64 {
		b.WriteString(enc[:64])
		b.WriteByte('\n')
		enc = enc[64:]
	}
	if len(enc) > 0 {
		b.WriteString(enc)
		b.WriteByte('\n')
	}
	b.WriteString("-----END CERTIFICATE-----\n")
	return b.Bytes()
}
