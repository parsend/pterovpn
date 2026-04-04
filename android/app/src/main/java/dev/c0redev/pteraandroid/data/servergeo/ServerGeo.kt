package dev.c0redev.pteraandroid.data.servergeo

data class ServerGeo(
    val countryCode: String,
    val countryName: String,
    val asnLabel: String,
    val flagEmoji: String,
)

fun countryCodeToFlagEmoji(code: String): String {
    if (code.length != 2) return "🌐"
    val a = code[0].uppercaseChar()
    val b = code[1].uppercaseChar()
    if (a !in 'A'..'Z' || b !in 'A'..'Z') return "🌐"
    val cp1 = 0x1F1E6 + (a - 'A')
    val cp2 = 0x1F1E6 + (b - 'A')
    return String(Character.toChars(cp1)) + String(Character.toChars(cp2))
}

fun serverHostFromField(serverTcp: String): String {
    val s = serverTcp.trim()
    if (s.isEmpty()) return ""
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end > 1) return s.substring(1, end)
        return s
    }
    val colon = s.lastIndexOf(':')
    if (colon > 0) {
        val after = s.substring(colon + 1)
        if (after.isNotEmpty() && after.all { it.isDigit() }) {
            return s.substring(0, colon)
        }
    }
    return s
}
