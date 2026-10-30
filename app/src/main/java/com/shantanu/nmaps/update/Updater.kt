package com.shantanu.nmaps.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.shantanu.nmaps.BuildConfig
import com.shantanu.nmaps.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published on GitHub Releases. */
data class Release(val build: Int, val version: String, val notes: String, val apkUrl: String, val apkBytes: Long, val page: String)

/**
 * In-app updates from the repository's GitHub Releases: CI tags every green build on master as
 * `v<version>-build.<n>`, so a release is newer when its build number beats [BuildConfig.BUILD_NUMBER].
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/SutharShantanu/NothingMaps/releases/latest"

    /** The newest release, or null when up to date. Throws when the check can't be made. */
    suspend fun check(): Release? = newer(Http.json(LATEST), BuildConfig.BUILD_NUMBER)

    /** The release in [json] if it beats [current]; null when it doesn't or has no APK. Throws when [json] isn't a release. */
    fun newer(json: JSONObject, current: Int): Release? {
        // Rate limits and outages come back as JSON with a message instead of a release.
        if (!json.has("tag_name")) throw IOException(json.optString("message").ifBlank { "No release information" })
        return parse(json)?.takeIf { it.build > current }
    }

    fun parse(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        val build = buildOf(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk")) continue
            // The release APK is the one to ship; a debug one only if that's all there is.
            if (apk == null || (name.contains("release") && !apk.optString("name").contains("release"))) apk = a
        }
        val chosen = apk ?: return null
        return Release(
            build = build,
            version = tag.removePrefix("v").substringBefore("-build"),
            notes = json.optString("body").trim(),
            apkUrl = chosen.optString("browser_download_url"),
            apkBytes = chosen.optLong("size"),
            page = json.optString("html_url"),
        )
    }

    /** "v1.0.0-build.42" → 42, "v1.0.42" → 42. */
    fun buildOf(tag: String): Int? {
        Regex("""-build\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""\.([1-9]\d*)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""^v?([1-9]\d*)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** Downloads the APK into the shareable cache folder, reporting 0..1 as it goes. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "maps-build-${release.build}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", Http.USER_AGENT)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /** Hands the APK to the system installer; Android asks once to allow installs from Maps. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
