package app.screentextcopy

import android.content.Context
import android.icu.util.ULocale
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Android's built-in on-device translation (Android 12+). It works only when the phone maker
 * provides the translation engine; on phones without it, [available] returns false and the app
 * offers a translator app instead. Arabic text is translated to English, anything else to Arabic.
 */
object SystemTranslator {
    private val executor = Executors.newSingleThreadExecutor()

    private fun manager(c: Context): TranslationManager? = try {
        c.getSystemService(TranslationManager::class.java)
    } catch (_: Exception) {
        null
    }

    /** True when Arabic ↔ English translation is offered on this phone. */
    fun available(c: Context): Boolean = try {
        val tm = manager(c)
        if (tm == null) {
            false
        } else {
            val caps = tm.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
            val ok = caps.any { cap ->
                val s = cap.sourceSpec.locale.language
                val t = cap.targetSpec.locale.language
                cap.state != TranslationCapability.STATE_NOT_AVAILABLE &&
                    ((s == "ar" && t == "en") || (s == "en" && t == "ar"))
            }
            AppLog.i("system translation: ${caps.size} language pairs, Arabic/English=${if (ok) "yes" else "no"}")
            ok
        }
    } catch (e: Exception) {
        AppLog.w("system translation check failed: ${e.javaClass.simpleName} ${e.message}")
        false
    }

    /** Translates [text]; [done] receives the result, or null on failure (called on a background thread). */
    fun translate(c: Context, text: String, done: (String?) -> Unit) {
        val tm = manager(c)
        if (tm == null) {
            done(null)
            return
        }
        val toArabic = !Word.isRtl(text.trim())
        val src = ULocale(if (toArabic) "en" else "ar")
        val dst = ULocale(if (toArabic) "ar" else "en")
        try {
            val context = TranslationContext.Builder(
                TranslationSpec(src, TranslationSpec.DATA_FORMAT_TEXT),
                TranslationSpec(dst, TranslationSpec.DATA_FORMAT_TEXT)
            ).build()
            tm.createOnDeviceTranslator(context, executor, Consumer { translator ->
                if (translator == null) {
                    AppLog.w("system translation: no translator for ${src.language}→${dst.language}")
                    done(null)
                    return@Consumer
                }
                val request = TranslationRequest.Builder()
                    .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text)))
                    .build()
                translator.translate(request, CancellationSignal(), executor, Consumer { response ->
                    val values = response.translationResponseValues
                    val out = if (values.size() > 0) values.valueAt(0).text?.toString() else null
                    AppLog.i("system translation ${src.language}→${dst.language}: ${if (out.isNullOrBlank()) "empty" else "ok"}")
                    try {
                        translator.destroy()
                    } catch (_: Exception) {
                    }
                    done(out)
                })
            })
        } catch (e: Exception) {
            AppLog.e("system translation failed", e)
            done(null)
        }
    }
}
