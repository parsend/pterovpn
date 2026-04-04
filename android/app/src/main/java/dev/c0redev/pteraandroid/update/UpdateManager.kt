package dev.c0redev.pteraandroid.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.c0redev.pteraandroid.BuildConfig
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
        private const val MIN_APK_BYTES = 64 * 1024L
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
        UpdatePrefs.setRemoteReleaseTag(context, candidate.tag)
        if (!isNewer(candidate.tag, currentVersion)) return false

        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val outFile = File(dir, "install.apk")
        val partFile = File(dir, "install.apk.part")
        downloadApkToFile(candidate.apkUrl, partFile, outFile)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, outFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("apk", uri)
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun downloadApkToFile(url: String, partFile: File, outFile: File) {
        partFile.delete()
        outFile.delete()
        partFile.parentFile?.mkdirs()

        val conn = openConnection(url)
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")

        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("download failed http=${conn.responseCode}")
        }

        conn.inputStream.use { input ->
            partFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()

        if (!looksLikeApkZip(partFile)) {
            partFile.delete()
            throw IllegalStateException("downloaded file is not a valid apk")
        }
        if (!partFile.renameTo(outFile)) {
            partFile.copyTo(outFile, overwrite = true)
            partFile.delete()
        }
    }

    private fun looksLikeApkZip(f: File): Boolean {
        if (f.length() < MIN_APK_BYTES) return false
        return f.inputStream().use { ins ->
            val buf = ByteArray(2)
            ins.read(buf) == 2 && buf[0] == 0x50.toByte() && buf[1] == 0x4b.toByte()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PteraVPN/${BuildConfig.VERSION_NAME}")
        return c
    }

    private fun httpGet(url: String): String? {
        val conn = openConnection(url)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        if (conn.responseCode !in 200..299) return null
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }
}
