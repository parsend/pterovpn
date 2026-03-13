package transport

import (
	"fmt"
	"io"
	"strings"
)

const (
	NameXOR  = "xor"
	NameMTLS = "mtls"

	prefaceVersion = 1
	idXOR          = 1
	idMTLS         = 2
)

var prefaceMagic = []byte{'P', 'T', 'T', 'R'}

func Normalize(name string) string {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case NameMTLS:
		return NameMTLS
	default:
		return NameXOR
	}
}

func WritePreface(w io.Writer, name string) error {
	var id byte
	switch Normalize(name) {
	case NameMTLS:
		id = idMTLS
	default:
		id = idXOR
	}
	if _, err := w.Write(prefaceMagic); err != nil {
		return err
	}
	if _, err := w.Write([]byte{prefaceVersion, id}); err != nil {
		return err
	}
	return nil
}

func ReadPreface(r io.Reader) (string, error) {
	buf := make([]byte, len(prefaceMagic)+2)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	if string(buf[:len(prefaceMagic)]) != string(prefaceMagic) {
		return "", fmt.Errorf("bad transport preface")
	}
	if buf[len(prefaceMagic)] != prefaceVersion {
		return "", fmt.Errorf("bad transport version")
	}
	switch buf[len(prefaceMagic)+1] {
	case idXOR:
		return NameXOR, nil
	case idMTLS:
		return NameMTLS, nil
	default:
		return "", fmt.Errorf("bad transport id")
	}
}
