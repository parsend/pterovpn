package clientlog

import (
	"fmt"
	"log"
	"strings"
)

func emit(tag, msg string) {
	log.Output(2, tag+"\t"+msg)
}

func OK(format string, args ...interface{}) {
	emit("OK", fmt.Sprintf(format, args...))
}

func Traffic(format string, args ...interface{}) {
	emit("TRAFFIC", fmt.Sprintf(format, args...))
}

func Drop(format string, args ...interface{}) {
	emit("DROP", fmt.Sprintf(format, args...))
}

func DPI(format string, args ...interface{}) {
	emit("DPI", fmt.Sprintf(format, args...))
}

func Err(format string, args ...interface{}) {
	emit("ERR", fmt.Sprintf(format, args...))
}

func Info(format string, args ...interface{}) {
	emit("INFO", fmt.Sprintf(format, args...))
}

func Warn(format string, args ...interface{}) {
	emit("WARN", fmt.Sprintf(format, args...))
}

func InferTag(line string) string {
	if idx := strings.Index(line, "\t"); idx > 0 && idx <= 8 {
		tag := line[:idx]
		switch tag {
		case "OK", "TRAFFIC", "DROP", "DPI", "ERR", "INFO", "WARN":
			return tag
		}
	}
	lower := strings.ToLower(line)
	switch {
	case strings.Contains(lower, "handshake failed"), strings.Contains(lower, "junk write failed"),
		strings.Contains(lower, "tcp dial server failed"), strings.Contains(lower, "tcp connect frame failed"),
		strings.Contains(lower, "precheck"), strings.Contains(lower, "pre-check"):
		if strings.Contains(lower, "reset") || strings.Contains(lower, "timeout") {
			return "DPI"
		}
		return "DPI"
	case strings.Contains(lower, "timeout"):
		return "DROP"
	case strings.Contains(lower, "udp read failed"), strings.Contains(lower, "udp send failed"),
		strings.Contains(lower, "udp channel read failed"), strings.Contains(lower, "dispatch write error"):
		return "DROP"
	case strings.Contains(lower, "connection reset"), strings.Contains(lower, "reset by peer"):
		if strings.Contains(lower, "handshake") || strings.Contains(lower, "junk") || strings.Contains(lower, "dial") {
			return "DPI"
		}
		return "DROP"
	case strings.Contains(lower, "tcp connect "), strings.Contains(lower, "udp assoc"),
		strings.Contains(lower, "tcp closed"), strings.Contains(lower, "udp channel "):
		return "TRAFFIC"
	case strings.Contains(lower, "connected"), strings.Contains(lower, "ready"),
		strings.Contains(lower, "netstack"), strings.Contains(lower, "listening"):
		return "OK"
	case strings.Contains(lower, "failed"), strings.Contains(lower, "error"):
		return "ERR"
	default:
		return "INFO"
	}
}

func LinePayload(line string) string {
	for _, tag := range []string{"OK\t", "TRAFFIC\t", "DROP\t", "DPI\t", "ERR\t", "INFO\t", "WARN\t"} {
		if idx := strings.Index(line, tag); idx >= 0 {
			return line[:idx] + "[" + tag[:len(tag)-1] + "] " + line[idx+len(tag):]
		}
	}
	return line
}
