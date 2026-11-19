package app.screentextcopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Offline text recognition with Tesseract (high-accuracy English and Arabic models in the APK). */
object OcrEngine {
    private const val MIN_CONFIDENCE = 45f
    private const val MIN_CONFIDENCE_RTL = 30f

    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null

    private fun dataDir(context: Context) = File(context.filesDir, "tesseract")

    /** Copies the model files from the APK to app storage (once per app version). */
    private fun ensureModels(context: Context) {
        val tessdata = File(dataDir(context), "tessdata")
        tessdata.mkdirs()
        val marker = File(tessdata, ".version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (marker.exists() && marker.readText() == version) return

        val names = context.assets.list("tessdata") ?: emptyArray()
        for (name in names) {
            if (!name.endsWith(".traineddata")) continue
            context.assets.open("tessdata/$name").use { input ->
                File(tessdata, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText(version)
    }

    @Synchronized
    private fun engine(context: Context): TessBaseAPI {
        val app = context.applicationContext
        ensureModels(app)
        val languages = Prefs.ocrLanguages(app)
        val current = api
        if (current != null && loadedLanguages == languages) return current

        current?.recycle()
        val tess = TessBaseAPI()
        if (!tess.init(dataDir(app).absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            tess.recycle()
            api = null
            loadedLanguages = null
            throw IllegalStateException("Text recognition models could not be loaded ($languages)")
        }
        tess.setVariable("preserve_interword_spaces", "1")
        api = tess
        loadedLanguages = languages
        return tess
    }

    /** Stops a running recognition (for example when the capture screen closes). */
    fun stop() {
        try {
            api?.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * Finds words in the screenshot with their positions.
     * Areas already covered by directly read text are blanked first, so they are
     * not recognized twice and the engine has less to process.
     */
    fun recognizeWords(context: Context, screenshot: Bitmap, skip: List<RectF>): List<Word> {
        val prepared = screenshot.copy(Bitmap.Config.ARGB_8888, true)
        if (skip.isNotEmpty()) {
            val canvas = Canvas(prepared)
            val paint = Paint().apply { color = Color.WHITE }
            for (r in skip) {
                canvas.drawRect(r.left - 3, r.top - 3, r.right + 3, r.bottom + 3, paint)
            }
        }

        val words: List<Word>
        synchronized(this) {
            val tess = engine(context)
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(prepared)
            tess.getUTF8Text() // runs recognition
            words = collectWords(tess, 1f, 0f, 0f, MIN_CONFIDENCE, MIN_CONFIDENCE_RTL)
            tess.clear()
        }
        prepared.recycle()
        return words
    }

    /**
     * Focused second look at one strip of the screen (used when a tap finds no word).
     * The strip is enlarged and scanned for scattered words with a low confidence bar,
     * so small or faint text that the full pass skipped can still be selected.
     */
    fun recognizeRegion(context: Context, screenshot: Bitmap, region: Rect): List<Word> {
        val r = Rect(region)
        if (!r.intersect(0, 0, screenshot.width, screenshot.height) || r.width() < 4 || r.height() < 4) {
            return emptyList()
        }
        val scale = if (r.height() < 120) 3f else 2f
        val crop = Bitmap.createBitmap(screenshot, r.left, r.top, r.width(), r.height())
        val big = Bitmap.createScaledBitmap(crop, (r.width() * scale).toInt(), (r.height() * scale).toInt(), true)
        if (crop !== screenshot && crop !== big) crop.recycle()

        val words: List<Word>
        synchronized(this) {
            val tess = engine(context)
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            tess.setImage(big)
            tess.getUTF8Text()
            words = collectWords(tess, scale, r.left.toFloat(), r.top.toFloat(), 10f, 5f)
            tess.clear()
        }
        big.recycle()
        return words
    }

    /** Reads word boxes from the last recognition, mapping them back to screenshot pixels. */
    private fun collectWords(
        tess: TessBaseAPI, scale: Float, offsetX: Float, offsetY: Float, minLatin: Float, minRtl: Float
    ): List<Word> {
        val words = ArrayList<Word>()
        val it = tess.resultIterator ?: return words
        try {
            it.begin()
            var para = LineIds.newId()
            var line = LineIds.newId()
            var first = true
            do {
                if (!first && it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) {
                    para = LineIds.newId()
                    line = LineIds.newId()
                } else if (!first && it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                    line = LineIds.newId()
                }
                first = false
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                // Arabic words get lower confidence scores even when correct, so they use a lower bar.
                val minConf = if (text != null && Word.isRtl(text)) minRtl else minLatin
                if (!text.isNullOrEmpty() && conf >= minConf && rect != null && !rect.isEmpty) {
                    val box = RectF(
                        rect.left / scale + offsetX, rect.top / scale + offsetY,
                        rect.right / scale + offsetX, rect.bottom / scale + offsetY
                    )
                    words.add(Word(text, box, line, para, fromImage = true))
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        return words
    }
}
