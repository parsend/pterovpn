package dev.c0redev.pteraandroid.domain.model

object ProtectionPresets {

    fun balanced(): ProtectionOptions = ProtectionOptions(
        obfuscation = "enhanced",
        junkCount = 4,
        junkMin = 64,
        junkMax = 512,
        padS1 = 4,
        padS2 = 4,
        padS3 = 4,
        padS4 = 24,
        preCheck = false,
        magicSplit = "1,2,2",
        junkStyle = "random",
        flushPolicy = "perChunk",
    )

    fun strict(): ProtectionOptions = ProtectionOptions(
        obfuscation = "enhanced",
        junkCount = 8,
        junkMin = 128,
        junkMax = 896,
        padS1 = 12,
        padS2 = 12,
        padS3 = 12,
        padS4 = 48,
        preCheck = false,
        magicSplit = "2,2,1",
        junkStyle = "random",
        flushPolicy = "perChunk",
    )

    fun suggestFromMetrics(records: List<SessionRecord>): ProtectionOptions {
        val tail = records.takeLast(10)
        if (tail.size < 2) return balanced()
        val bad = tail.count { r ->
            when (r.errorType?.lowercase()) {
                "timeout", "reset", "unknown" -> true
                else -> false
            }
        }
        return if (bad >= 3) strict() else balanced()
    }
}
