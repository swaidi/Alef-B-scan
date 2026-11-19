package app.screentextcopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Where the capture screen is shown: a layer over the current app, or a full screen of its own. */
interface CaptureHost {
    /** Closes the capture screen, leaving the user where they were. */
    fun close()

    /** Shows a dialog above the capture screen. */
    fun show(builder: MaterialAlertDialogBuilder)

    /** False once the capture screen has closed. */
    val alive: Boolean
}

/**
 * The frozen screen with selectable words, the floating action bar and shortcuts.
 * Text read directly from apps is selectable at once; text inside images (and inside
 * apps that do not report letter positions) is recognized in the background.
 */
class CaptureUi(private val ctx: Context, private val host: CaptureHost) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var generation = 0
    private var ocrRunning = false

    private var screenshot: Bitmap? = null
    /** Words with exact positions reported by the apps on screen. */
    private var precise: List<Word> = emptyList()
    /** Whole text blocks without letter positions; split into real words after recognition. */
    private var coarse: List<Word> = emptyList()

    /** Back key or back gesture closes the capture screen. */
    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) host.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private lateinit var selectionView: SelectionView
    private lateinit var statusText: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var actionBar: GlowBar

    // ---------- Loading ----------

    fun load(bmp: Bitmap, direct: List<Word>) {
        screenshot = bmp
        precise = direct.filterNot { it.coarse }
        coarse = direct.filter { it.coarse }
        selectionView.bitmap = bmp
        selectionView.setWords(direct)
        selectionView.clearSelection()
        actionBar.visibility = View.GONE
        root.requestFocus()
        selectionView.post { selectionView.playEdgeGlow() }
        recognizeImages(bmp)
    }

    fun destroy() {
        destroyed = true
        generation++
        OcrEngine.stop()
        worker.shutdownNow()
    }

    private fun readyStatus() = "Tap a word, or circle text"

    private fun recognizeImages(bmp: Bitmap) {
        val gen = ++generation
        ocrRunning = true
        setStatus(
            if (precise.isEmpty()) "Reading text…" else "${readyStatus()}  ·  reading images…",
            busy = true
        )
        // Only exact app text is skipped; blocks without letter positions are recognized
        // so that every word in them can be selected on its own.
        val skip = precise.map { it.box }
        worker.execute {
            val result = runCatching { OcrEngine.recognizeWords(ctx, bmp, skip) }
            main.post {
                if (destroyed || gen != generation) return@post
                ocrRunning = false
                result.fold(
                    onSuccess = { ocr ->
                        val fromImages = ocr.filterNot { overlaps(it.box, precise, 0.3f) }
                        val merged = precise + splitBlocks(fromImages)
                        selectionView.setWords(merged)
                        setStatus(
                            if (merged.isEmpty()) "No text found. Tap where text is to look again." else readyStatus(),
                            busy = false
                        )
                    },
                    onFailure = { setStatus("Image text could not be read: ${it.message}", busy = false) }
                )
            }
        }
    }

    /**
     * Replaces each whole text block with the recognized words inside it. When the number of
     * recognized words matches the block's own words, the app's exact text is kept for each one.
     */
    private fun splitBlocks(ocr: List<Word>): List<Word> {
        val used = HashSet<Word>()
        val out = ArrayList<Word>()
        for (block in coarse) {
            val area = RectF(block.box).apply { inset(-6f, -6f) }
            val inside = ocr.filter { it !in used && area.contains(it.box.centerX(), it.box.centerY()) }
            if (inside.isEmpty()) {
                out.add(block)
                continue
            }
            used.addAll(inside)
            val tokens = block.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size == inside.size) {
                inside.forEachIndexed { i, w -> out.add(Word(tokens[i], w.box, w.line, block.para, fromImage = false)) }
            } else {
                inside.forEach { w -> out.add(Word(w.text, w.box, w.line, block.para, fromImage = true)) }
            }
        }
        out.addAll(ocr.filterNot { it in used })
        return out
    }

    /** True when [box] is covered by any of [others] by more than [fraction] of its area. */
    private fun overlaps(box: RectF, others: List<Word>, fraction: Float): Boolean {
        val area = box.width() * box.height()
        if (area <= 0f) return true
        val i = RectF()
        for (o in others) {
            if (i.setIntersect(box, o.box) && i.width() * i.height() > area * fraction) return true
        }
        return false
    }

    /** A tap that found no word: take a closer look at that strip of the screen. */
    private fun rescanAt(p: PointF) {
        val bmp = screenshot ?: return
        if (selectionView.height <= 0) return
        val sy = bmp.height / selectionView.height.toFloat()
        val half = (dp(36) * sy).toInt()
        val region = Rect(0, (p.y - half).toInt(), bmp.width, (p.y + half).toInt())
        val gen = generation
        setStatus("Looking for text here…", busy = true)
        worker.execute {
            val found = runCatching { OcrEngine.recognizeRegion(ctx, bmp, region) }.getOrDefault(emptyList())
            main.post {
                if (destroyed || gen != generation) return@post
                val fresh = found.filterNot { overlaps(it.box, selectionView.allWords(), 0.5f) }
                selectionView.addWords(fresh)
                setStatus(if (ocrRunning) "${readyStatus()}  ·  reading images…" else readyStatus(), busy = ocrRunning)
                if (!selectionView.selectWordAt(p)) {
                    Toast.makeText(ctx, "No text found at that spot", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- Layout ----------

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun pill(alpha: Int = 225) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.argb(alpha, 20, 28, 44))
    }

    private fun buildLayout() {
        selectionView = SelectionView(ctx).apply {
            onSelectionChanged = { has ->
                if (has) {
                    resetExpand()
                    showActionBar()
                } else {
                    actionBar.visibility = View.GONE
                }
            }
            onTapMiss = { p -> rescanAt(p) }
        }
        root.setBackgroundColor(Color.BLACK)
        root.addView(selectionView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Top: status and close.
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill()
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        statusSpinner = ProgressBar(ctx).apply { visibility = View.GONE }
        top.addView(statusSpinner, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        statusText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        top.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(barButton("Close") { host.close() })
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(dp(16), dp(36), dp(16), 0) })

        // Floating actions placed right under (or above) the selection, with a soft animated glow.
        val bar = GlowBar(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(glowSize + dp(6), glowSize + dp(2), glowSize + dp(6), glowSize + dp(2))
            visibility = View.GONE
        }
        bar.addView(actionButton("Copy", R.drawable.ic_action_copy) { copy() })
        bar.addView(actionButton("Share", R.drawable.ic_action_share) { share() })
        expandButton = actionButton("Sentence", R.drawable.ic_action_sentence) { expandSelection() }
        bar.addView(expandButton)
        bar.addView(actionButton("All", R.drawable.ic_action_select_all) { selectionView.selectAll() })
        // Tap runs the default (starred) shortcut; long-press chooses another one.
        shortcutButton = actionButton("Smart", R.drawable.ic_action_shortcut) { runDefaultShortcut() }
        shortcutButton.setOnLongClickListener {
            pickShortcut()
            true
        }
        bar.addView(shortcutButton)
        actionBar = bar
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
    }

    private fun barButton(label: String, action: () -> Unit) =
        Button(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(12), paddingTop, dp(12), paddingBottom)
            setOnClickListener { action() }
        }

    // ---------- Action buttons: icon with a one-line label, or icon only when space is tight ----------

    private val actionButtons = ArrayList<Button>()
    private var compactBar = false

    private fun actionButton(label: String, icon: Int, action: () -> Unit) =
        Button(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minWidth = dp(52)
            minimumWidth = dp(52)
            maxWidth = dp(104)
            compoundDrawablePadding = dp(2)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { action() }
            actionButtons.add(this)
            setAction(this, label, icon)
        }

    private fun setAction(button: Button, label: String, icon: Int) {
        button.tag = label
        button.contentDescription = label
        button.tooltipText = label // long-press shows the name in icon-only mode
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
        button.text = if (compactBar) "" else label
    }

    private fun setCompact(compact: Boolean) {
        compactBar = compact
        for (b in actionButtons) {
            b.text = if (compact) "" else (b.tag as? String ?: "")
            b.minWidth = if (compact) dp(48) else dp(52)
            b.minimumWidth = b.minWidth
        }
    }

    // ---------- Shortcut button ----------

    private lateinit var shortcutButton: Button

    /** Shows the default shortcut's name on the button, or hides it if there are none. */
    private fun refreshShortcutButton() {
        val s = ShortcutStore.defaultShortcut(ctx)
        if (s == null) {
            shortcutButton.visibility = View.GONE
            return
        }
        shortcutButton.visibility = View.VISIBLE
        val label = if (s.type == Shortcut.TYPE_SMART) "Smart" else s.name
        setAction(shortcutButton, label, R.drawable.ic_action_shortcut)
        shortcutButton.tooltipText = null // long-press opens the shortcut list instead
    }

    private fun runShortcut(s: Shortcut) {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        ShortcutRunner.run(ctx, host, s, text, done = { host.close() }, other = { pickShortcut() })
    }

    private fun runDefaultShortcut() {
        val s = ShortcutStore.defaultShortcut(ctx) ?: return pickShortcut()
        runShortcut(s)
    }

    private fun pickShortcut() {
        val list = ShortcutStore.load(ctx)
        if (list.isEmpty()) {
            Toast.makeText(ctx, "Add shortcuts in Alef_B_scan settings", Toast.LENGTH_LONG).show()
            return
        }
        val def = ShortcutStore.defaultIndex(ctx, list.size)
        val names = list.mapIndexed { i, s -> if (i == def) "★  ${s.name}" else s.name }.toTypedArray()
        host.show(
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Run shortcut")
                .setItems(names) { _, i -> runShortcut(list[i]) }
                .setNegativeButton("Cancel", null)
        )
    }

    // ---------- Step-by-step selection: word → sentence → paragraph ----------

    private lateinit var expandButton: Button
    private var expandLevel = 0 // 0 = as selected, 1 = sentence, 2 = paragraph

    private fun resetExpand() {
        expandLevel = 0
        setAction(expandButton, "Sentence", R.drawable.ic_action_sentence)
        expandButton.visibility = if (selectionView.isAllSelected()) View.GONE else View.VISIBLE
    }

    private fun expandSelection() {
        if (expandLevel == 0 && selectionView.expandToSentence()) {
            expandLevel = 1
            setAction(expandButton, "Paragraph", R.drawable.ic_action_paragraph)
        } else {
            // Already a full sentence (or second tap): go to the whole paragraph.
            selectionView.expandToParagraph()
            expandLevel = 2
            expandButton.visibility = View.GONE
        }
        showActionBar()
    }

    /** Shows the action bar just below the selection, or above it when there is no room. */
    private fun showActionBar() {
        val bounds = selectionView.selectionBounds() ?: return
        val bar = actionBar
        val screenW = selectionView.width.toFloat()
        val screenH = selectionView.height.toFloat()
        val margin = dp(12).toFloat()
        // The view includes a transparent glow area around the visible bar.
        val g = bar.glowSize.toFloat()

        refreshShortcutButton()

        // Use icons with labels when they fit; on narrow screens or large fonts, icons only.
        fun measureVisibleWidth(): Float {
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return bar.measuredWidth - 2 * g
        }
        setCompact(false)
        if (measureVisibleWidth() > screenW - 2 * margin) {
            setCompact(true)
            measureVisibleWidth()
        }
        val w = bar.measuredWidth.toFloat() - 2 * g
        val h = bar.measuredHeight.toFloat() - 2 * g

        var y = bounds.bottom + dp(10)
        if (y + h > screenH - dp(24)) y = bounds.top - h - dp(16)
        if (y < dp(96)) y = (screenH - h) / 2f // very tall selection: keep it on screen
        val maxX = (screenW - w - margin).coerceAtLeast(margin)
        val x = (bounds.centerX() - w / 2f).coerceIn(margin, maxX)

        bar.x = x - g
        bar.y = y - g
        bar.visibility = View.VISIBLE
    }

    private fun setStatus(text: String, busy: Boolean) {
        statusText.text = text
        statusSpinner.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ---------- Actions ----------

    private fun copy() {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        host.close()
    }

    private fun share() {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            host.close()
        } catch (_: Exception) {
            Toast.makeText(ctx, "Sharing is not available", Toast.LENGTH_SHORT).show()
        }
    }

    // Kept last: every field above must exist before the layout is built.
    init {
        buildLayout()
        root.isFocusableInTouchMode = true
    }
}
