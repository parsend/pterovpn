package dev.c0redev.pteraandroid.json

import org.json.JSONObject

fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotEmpty() }
}
