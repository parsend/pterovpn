package dev.c0redev.pteraandroid.domain.model

import dev.c0redev.pteraandroid.json.optNullableString
import org.json.JSONObject

data class ProtectionOptions(
    val obfuscation: String? = null,
    val junkCount: Int = 0,
    val junkMin: Int = 0,
    val junkMax: Int = 0,
    val padS1: Int = 0,
    val padS2: Int = 0,
    val padS3: Int = 0,
    val padS4: Int = 0,
    val preCheck: Boolean = false,
    val magicSplit: String? = null,
    val junkStyle: String? = null,
    val flushPolicy: String? = null,
    val obfAutoRotate: Boolean = false,
    val obfRotateEveryM: Int = 0,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        obfuscation?.let { j.put("obfuscation", it) }
        if (junkCount != 0) j.put("junkCount", junkCount)
        if (junkMin != 0) j.put("junkMin", junkMin)
        if (junkMax != 0) j.put("junkMax", junkMax)
        if (padS1 != 0) j.put("padS1", padS1)
        if (padS2 != 0) j.put("padS2", padS2)
        if (padS3 != 0) j.put("padS3", padS3)
        if (padS4 != 0) j.put("padS4", padS4)
        j.put("preCheck", preCheck)
        magicSplit?.let { j.put("magicSplit", it) }
        junkStyle?.let { j.put("junkStyle", it) }
        flushPolicy?.let { j.put("flushPolicy", it) }
        if (obfAutoRotate) j.put("obfAutoRotate", true)
        if (obfRotateEveryM > 0) j.put("obfRotateEveryM", obfRotateEveryM)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): ProtectionOptions = ProtectionOptions(
            obfuscation = j.optNullableString("obfuscation"),
            junkCount = j.optInt("junkCount", 0),
            junkMin = j.optInt("junkMin", 0),
            junkMax = j.optInt("junkMax", 0),
            padS1 = j.optInt("padS1", 0),
            padS2 = j.optInt("padS2", 0),
            padS3 = j.optInt("padS3", 0),
            padS4 = j.optInt("padS4", 0),
            preCheck = j.optBoolean("preCheck", false),
            magicSplit = j.optNullableString("magicSplit"),
            junkStyle = j.optNullableString("junkStyle"),
            flushPolicy = j.optNullableString("flushPolicy"),
            obfAutoRotate = j.optBoolean("obfAutoRotate", false),
            obfRotateEveryM = j.optInt("obfRotateEveryM", 0),
        )
    }
}
