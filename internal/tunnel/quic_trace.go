package tunnel

import (
	"os"
	"strings"
	"sync/atomic"
)

var quicTraceAtomic atomic.Bool

func SetQUICTrace(on bool) {
	quicTraceAtomic.Store(on)
}

func quicTraceOn() bool {
	if quicTraceAtomic.Load() {
		return true
	}
	v := strings.TrimSpace(os.Getenv("PTERA_QUIC_TRACE"))
	return v == "1" || strings.EqualFold(v, "true")
}

func QUICTraceEnabled() bool {
	return quicTraceOn()
}
