package app.screentextcopy

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The test report: a small text file on the phone that records what the app did, how long each
 * step took and any errors, so a problem can be traced. Screen text is NOT stored unless the user
 * turns on "Include text in report" for a test; otherwise only its length is written.
 */
object AppLog {
    private const val FILE = "alefbscan-report.txt"
    private const val MAX_BYTES = 400_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var dir: File? = null

    /** Mirrors the "Include text in report" setting. */
    @Volatile
    var includeText = false

    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.applicationContext.filesDir
        includeText = Prefs.logText(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashHandler) Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    @Synchronized
    private fun write(level: String, msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText("${fmt.format(Date())} $level $msg\n")
        } catch (_: Exception) {
        }
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, t: Throwable? = null) = write("E", msg + (t?.let { "\n" + stack(it) } ?: ""))

    /** Screen text for the report: the text itself only when allowed, otherwise just its length. */
    fun text(s: String): String =
        if (includeText) "\"" + s.replace("\n", " ⏎ ").take(120) + "\"" else "<${s.length} chars>"

    /** The report with a short header about the phone and app, ready to share. */
    fun read(ctx: Context): String {
        val header = buildString {
            append("Alef_B_scan ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Trigger service: ${if (TriggerService.instance != null) "on" else "off"}\n")
            append("Text recognition: ${Prefs.ocrLanguages(ctx)}\n")
            append("----\n")
        }
        val body = try {
            file(ctx).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        return header + body
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Exception) {
        }
    }

    private fun stack(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().take(4000)

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            write("CRASH", "in ${t.name}: ${e.javaClass.simpleName}: ${e.message}\n" + stack(e))
            previous?.uncaughtException(t, e)
        }
    }

    /** Runs [block]; a failure is logged and swallowed, so one broken feature never stops the app. */
    inline fun <T> safe(what: String, default: T, block: () -> T): T = try {
        block()
    } catch (ex: Exception) {
        e("$what failed", ex)
        default
    }
}

/** Starts the report log as soon as the app process starts (screen or trigger service). */
class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
