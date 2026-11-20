package app.screentextcopy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the public GitHub repository's releases (same approach as Riyaly).
 * The internet is used only here: to check for a new version and download it.
 */
object Updater {
    private const val REPO = "swaidi/Alef-B-scan"

    data class Release(val versionCode: Int, val tag: String, val assetId: Long, val assetName: String)

    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Alef_B_scan/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 20000
            readTimeout = 60000
        }

    /** Latest release, or null when there is none. */
    fun latest(): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw HttpError(conn.responseCode)
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Release(code, tag, apk.getLong("id"), apk.getString("name"))
        } finally {
            conn.disconnect()
        }
    }

    /** Checks and remembers whether a newer version exists (for the dot on the Update button). */
    fun check(ctx: Context): Release? {
        val r = latest()
        Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis())
        val newer = r != null && r.versionCode > BuildConfig.VERSION_CODE
        Prefs.setUpdateTag(ctx, if (newer) r!!.tag else null)
        AppLog.i("update check: latest=${r?.tag} installed=${BuildConfig.VERSION_NAME}")
        return if (newer) r else null
    }

    /** Downloads the APK into the app's cache (GitHub redirects to a storage link). */
    fun download(ctx: Context, r: Release): File {
        var conn = open("https://api.github.com/repos/$REPO/releases/assets/${r.assetId}", "application/octet-stream")
        var hops = 0
        while (conn.responseCode in 300..399 && hops < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(next, "application/octet-stream")
            hops++
        }
        if (conn.responseCode != 200) throw HttpError(conn.responseCode)
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, r.assetName)
        conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out, 64 * 1024) } }
        conn.disconnect()
        return file
    }

    /** Opens Android's installer for the downloaded APK. Settings are kept: the signing key is the same. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** The Update button: check, ask, download and open the installer. */
    fun runFlow(a: MainActivity) {
        a.toast(a.getString(R.string.checking_updates))
        Thread {
            val result = runCatching { check(a) }
            a.runOnUiThread {
                a.refreshUpdateIcon()
                val err = result.exceptionOrNull()
                val r = result.getOrNull()
                when {
                    err != null -> {
                        AppLog.w("update check failed: ${err.javaClass.simpleName} ${err.message}")
                        a.toast(a.getString(R.string.no_internet))
                    }
                    r == null -> a.toast(a.getString(R.string.up_to_date))
                    else -> a.confirm(
                        a.getString(R.string.update_available, r.tag),
                        a.getString(R.string.update_install_q),
                        a.getString(R.string.update_install)
                    ) { downloadAndInstall(a, r) }
                }
            }
        }.start()
    }

    private fun downloadAndInstall(a: MainActivity, r: Release) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.toast(a.getString(R.string.update_allow_installs))
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        a.toast(a.getString(R.string.downloading_update))
        Thread {
            val file = try {
                download(a, r)
            } catch (e: Exception) {
                AppLog.e("update download", e)
                null
            }
            a.runOnUiThread {
                if (file == null) Toast.makeText(a, R.string.update_failed, Toast.LENGTH_LONG).show()
                else install(a, file)
            }
        }.start()
    }

    /** Quiet check at most once a day when the app opens; the Update button shows a dot if newer. */
    fun backgroundCheck(a: MainActivity) {
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(a) < 20L * 3600 * 1000) return
        Thread {
            AppLog.safe("background update check", Unit) { check(a) }
            a.runOnUiThread { a.refreshUpdateIcon() }
        }.start()
    }
}
