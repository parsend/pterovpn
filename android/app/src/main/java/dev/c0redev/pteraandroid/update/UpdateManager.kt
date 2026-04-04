package dev.c0redev.pteraandroid.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class UpdateManager {
    companion object {
        private const val API_LATEST = "https://api.github.com/repos/unitdevgcc/pterovpn/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    data class UpdateCandidate(
        val tag: String,
        val apkUrl: String,
        val assetName: String,
    )

    fun checkLatestRelease(): UpdateCandidate? {
        val raw = httpGet(API_LATEST) ?: return null
        val j = JSONObject(raw)
        val tag = j.optString("tag_name", "").trim()
        if (tag.isBlank()) return null

        val assets = j.optJSONArray("assets") ?: return null
        val best = pickBestApkAsset(assets) ?: return null
        return UpdateCandidate(tag = tag, apkUrl = best.second, assetName = best.first)
    }

    fun isNewer(tag: String, currentVersion: String): Boolean {
        if (currentVersion.isBlank()) return true
        val a = parseVersion(tag)
        val b = parseVersion(currentVersion)
        return compareVersion(a, b) > 0
    }

    fun checkAndInstall(context: Context, currentVersion: String): Boolean {
        val candidate = checkLatestRelease() ?: return false
        if (!isNewer(candidate.tag, currentVersion)) return false

        val outFile = File(context.cacheDir, "updates/ptera-android-latest.apk")
        outFile.parentFile?.mkdirs()
        downloadToFile(candidate.apkUrl, outFile)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, outFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    private fun pickBestApkAsset(assets: JSONArray): Pair<String, String>? {
        var best: Pair<String, String>? = null
        var bestScore = -1
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "").trim()
            val url = a.optString("browser_download_url", "").trim()
            if (name.isBlank() || url.isBlank()) continue
            val lower = name.lowercase(Locale.ROOT)
            if (!lower.endsWith(".apk")) continue

            var score = 0
            if (lower.contains("android")) score += 1
            if (lower.contains("arm64") || lower.contains("aarch64")) score += 3
            if (lower.contains("universal")) score += 2
            if (best == null || score > bestScore) {
                best = name to url
                bestScore = score
            }
        }

        if (best != null) return best

        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "").trim()
            val url = a.optString("browser_download_url", "").trim()
            if (name.lowercase(Locale.ROOT).endsWith(".apk") && url.isNotBlank()) {
                return name to url
            }
        }
        return null
    }

    private fun parseVersion(v: String): List<Int> {
        val s = v.trim().lowercase(Locale.ROOT).removePrefix("v")
        val parts = s.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val out = ArrayList<Int>(3)
        for (i in 0 until 3) out.add(parts.getOrElse(i) { 0 })
        return out
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    private fun downloadToFile(url: String, outFile: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "*/*")

        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("download failed http=${conn.responseCode}")
        }

        outFile.outputStream().use { os ->
            conn.inputStream.use { input ->
                input.copyTo(os)
            }
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        if (conn.responseCode !in 200..299) return null
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

