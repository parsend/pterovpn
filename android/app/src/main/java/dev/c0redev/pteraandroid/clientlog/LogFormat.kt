package dev.c0redev.pteraandroid.clientlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KNOWN = setOf("OK", "TRAFFIC", "DROP", "DPI", "ERR", "INFO", "WARN", "TRACE")

data class ParsedLogLine(val tag: String, val body: String)

fun parseLogLine(line: String): ParsedLogLine {
    val idx = line.indexOf('\t')
    if (idx > 0 && idx <= 8) {
        val t = line.substring(0, idx)
        if (t in KNOWN) {
            return ParsedLogLine(t, line.substring(idx + 1).trimStart())
        }
    }
    return ParsedLogLine(inferTag(line), line)
}

fun inferTag(line: String): String {
    val idx = line.indexOf('\t')
    if (idx > 0 && idx <= 8) {
        val tag = line.substring(0, idx)
        if (tag in KNOWN) return tag
    }
    val lower = line.lowercase()
    when {
        lower.contains("handshake failed") || lower.contains("junk write failed") ||
            lower.contains("tcp dial server failed") || lower.contains("tcp connect frame failed") ||
            lower.contains("precheck") || lower.contains("pre-check") -> return "DPI"
        lower.contains("timeout") -> return "DROP"
        lower.contains("udp read failed") || lower.contains("udp send failed") ||
            lower.contains("udp channel read failed") || lower.contains("dispatch write error") -> return "DROP"
        lower.contains("connection reset") || lower.contains("reset by peer") -> {
            if (lower.contains("handshake") || lower.contains("junk") || lower.contains("dial")) return "DPI"
            return "DROP"
        }
        lower.contains("tcp connect ") || lower.contains("udp assoc") ||
            lower.contains("tcp closed") || lower.contains("udp channel ") -> return "TRAFFIC"
        lower.contains("connected") || lower.contains("ready") ||
            lower.contains("netstack") || lower.contains("listening") -> return "OK"
        lower.contains("failed") || lower.contains("error") -> return "ERR"
        else -> return "INFO"
    }
}

@Composable
fun tagColor(tag: String): Color {
    val scheme = MaterialTheme.colorScheme
    return when (tag) {
        "OK" -> Color(0xFF2E7D32)
        "TRAFFIC" -> Color(0xFF1565C0)
        "DROP" -> Color(0xFFEF6C00)
        "DPI" -> Color(0xFF6A1B9A)
        "ERR" -> scheme.error
        "WARN" -> Color(0xFFF9A825)
        "TRACE" -> scheme.onSurfaceVariant.copy(alpha = 0.7f)
        "INFO" -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
}
