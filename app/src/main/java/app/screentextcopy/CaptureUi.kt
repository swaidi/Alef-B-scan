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
import android.os.SystemClock
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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Where the capture screen is shown: a layer over the current app, or a full screen of its own. */
interface CaptureHost {
    /** Closes the capture screen, leaving the user where they were. */
    fun close()

    /** Shows a dialog above the capture screen and returns it. */
    fun show(builder: MaterialAlertDialogBuilder): AlertDialog

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

    private val showDialog: ShowDialog = { b -> host.show(b) }

    // ---------- Loading ----------

    fun load(bmp: Bitmap, direct: List<Word>) {
        screenshot = bmp
        precise = direct.filterNot { it.coarse }
        coarse = direct.filter { it.coarse }
        AppLog.i("capture: ${bmp.width}x${bmp.height}, app text ${precise.size} words + ${coarse.size} blocks without letter positions")
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

    private fun readyStatus() = ctx.getString(R.string.cap_ready)
    private fun busyStatus() = ctx.getString(R.string.cap_reading_images)

    private fun recognizeImages(bmp: Bitmap) {
        val gen = ++generation
        ocrRunning = true
        setStatus(if (precise.isEmpty()) ctx.getString(R.string.cap_reading) else busyStatus(), busy = true)
        // Only exact app text is skipped; blocks without letter positions are recognized
        // so that every word in them can be selected on its own.
        val skip = precise.map { it.box }
        val started = SystemClock.elapsedRealtime()
        worker.execute {
            val result = runCatching { OcrEngine.recognizeWords(ctx, bmp, skip) }
            main.post {
                if (destroyed || gen != generation) return@post
                ocrRunning = false
                val ms = SystemClock.elapsedRealtime() - started
                result.fold(
                    onSuccess = { ocr ->
                        val fromImages = ocr.filterNot { overlaps(it.box, precise, 0.3f) }
                        val merged = precise + splitBlocks(fromImages)
                        AppLog.i("image text: ${ocr.size} words in ${ms} ms; ${merged.size} selectable words in total")
                        selectionView.setWords(merged)
                        setStatus(
                            if (merged.isEmpty()) ctx.getString(R.string.cap_no_text) else readyStatus(),
                            busy = false
                        )
                    },
                    onFailure = {
                        AppLog.e("image text failed after $ms ms", it)
                        setStatus(ctx.getString(R.string.cap_ocr_failed, it.message ?: ""), busy = false)
                    }
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
        var exact = 0
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
                exact++
                inside.forEachIndexed { i, w -> out.add(Word(tokens[i], w.box, w.line, block.para, fromImage = false)) }
            } else {
                inside.forEach { w -> out.add(Word(w.text, w.box, w.line, block.para, fromImage = true)) }
            }
        }
        if (coarse.isNotEmpty()) AppLog.i("text blocks: ${coarse.size}, split with exact app text: $exact")
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
        val half = (ctx.dp(36) * sy).toInt()
        val region = Rect(0, (p.y - half).toInt(), bmp.width, (p.y + half).toInt())
        val gen = generation
        setStatus(ctx.getString(R.string.cap_looking), busy = true)
        val started = SystemClock.elapsedRealtime()
        worker.execute {
            val found = runCatching { OcrEngine.recognizeRegion(ctx, bmp, region) }.getOrDefault(emptyList())
            main.post {
                if (destroyed || gen != generation) return@post
                val fresh = found.filterNot { overlaps(it.box, selectionView.allWords(), 0.5f) }
                selectionView.addWords(fresh)
                setStatus(if (ocrRunning) busyStatus() else readyStatus(), busy = ocrRunning)
                val hit = selectionView.selectWordAt(p)
                AppLog.i("re-scan at y=${p.y.toInt()}: ${found.size} words, ${fresh.size} new, word under finger: $hit (${SystemClock.elapsedRealtime() - started} ms)")
                if (!hit) Toast.makeText(ctx, R.string.cap_no_text_spot, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Layout ----------

    private fun dp(v: Int) = ctx.dp(v)

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
        top.addView(barButton(ctx.getString(R.string.close)) { host.close() })
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(dp(16), dp(36), dp(16), 0) })

        // Floating actions placed right under (or above) the selection, with a soft animated glow.
        val bar = GlowBar(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(glowSize + dp(6), glowSize + dp(2), glowSize + dp(6), glowSize + dp(2))
            visibility = View.GONE
            // The pinned shortcut stays the last button on the right, in English and Arabic.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        bar.addView(actionButton(ctx.getString(R.string.act_copy), R.drawable.ic_action_copy) { copy() })
        bar.addView(actionButton(ctx.getString(R.string.act_share), R.drawable.ic_action_share) { share() })
        expandButton = actionButton(ctx.getString(R.string.act_sentence), R.drawable.ic_action_sentence) { expandSelection() }
        bar.addView(expandButton)
        bar.addView(actionButton(ctx.getString(R.string.act_all), R.drawable.ic_action_select_all) { selectionView.selectAll() })
        // Tap runs the pinned shortcut; long-press opens the full list.
        shortcutButton = actionButton(ctx.getString(R.string.act_smart), R.drawable.ic_action_shortcut) { runPinnedShortcut() }
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

    // ---------- Pinned shortcut button ----------

    private lateinit var shortcutButton: Button

    /** Shows the pinned shortcut's name on the button, or hides it if there are none. */
    private fun refreshShortcutButton() {
        val s = ShortcutStore.pinned(ctx)
        if (s == null) {
            shortcutButton.visibility = View.GONE
            return
        }
        shortcutButton.visibility = View.VISIBLE
        val label = if (s.type == Shortcut.TYPE_SMART) ctx.getString(R.string.act_smart) else s.displayName(ctx)
        setAction(shortcutButton, label, R.drawable.ic_action_shortcut)
        shortcutButton.tooltipText = null // long-press opens the shortcut list instead
    }

    private fun runShortcut(s: Shortcut) = withSelectedText { text ->
        if (text.isBlank()) return@withSelectedText
        ShortcutRunner.run(
            ctx, host, s, text,
            copied = { result -> afterCopy(result) },
            done = { host.close() },
            other = { pickShortcut() }
        )
    }

    private fun runPinnedShortcut() {
        val s = ShortcutStore.pinned(ctx) ?: return pickShortcut()
        runShortcut(s)
    }

    /** The full shortcut list: tap runs one; hold one for Pin, Edit or Remove. */
    private fun pickShortcut() {
        val list = ShortcutStore.load(ctx)
        if (list.isEmpty()) {
            Toast.makeText(ctx, R.string.msg_add_shortcuts, Toast.LENGTH_LONG).show()
            return
        }
        val pin = ShortcutStore.pinnedIndex(ctx, list.size)
        val names = list.mapIndexed { i, s -> if (i == pin) "★  ${s.displayName(ctx)}" else s.displayName(ctx) }.toTypedArray()
        val dialog = host.show(
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.run_shortcut_title)
                .setItems(names) { _, i -> runShortcut(list[i]) }
                .setNegativeButton(R.string.cancel, null)
        )
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            dialog.dismiss()
            ShortcutEditor.showOptions(ctx, showDialog, position, withMoveUp = false) {
                refreshShortcutButton()
                if (actionBar.visibility == View.VISIBLE) showActionBar()
            }
            true
        }
    }

    // ---------- Step-by-step selection: word → sentence → paragraph ----------

    private lateinit var expandButton: Button
    private var expandLevel = 0 // 0 = as selected, 1 = sentence, 2 = paragraph

    private fun resetExpand() {
        expandLevel = 0
        setAction(expandButton, ctx.getString(R.string.act_sentence), R.drawable.ic_action_sentence)
        expandButton.visibility = if (selectionView.isAllSelected()) View.GONE else View.VISIBLE
    }

    private fun expandSelection() {
        if (expandLevel == 0 && selectionView.expandToSentence()) {
            expandLevel = 1
            setAction(expandButton, ctx.getString(R.string.act_paragraph), R.drawable.ic_action_paragraph)
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

    // ---------- Careful re-read of the selection ----------

    private var resolving = false

    /**
     * Gives the selected text to [action]. Lines that came from image recognition are first
     * re-read closely (wider crop, enlarged, both contrasts), which recovers cut-off letters
     * and fixes misread ones. Text read directly from apps is already exact and kept as is.
     */
    private fun withSelectedText(action: (String) -> Unit) {
        if (resolving) return
        val selected = selectionView.selectedWords()
        if (selected.isEmpty()) return
        val bmp = screenshot
        if (bmp == null || selected.none { it.fromImage }) {
            action(Word.join(selected))
            return
        }
        // Group the selection into its lines, keeping reading order.
        val lines = LinkedHashMap<Int, MutableList<Word>>()
        for (w in selected) lines.getOrPut(w.line) { ArrayList() }.add(w)
        val all = selectionView.allWords()

        resolving = true
        setStatus(ctx.getString(R.string.cap_reading_selection), busy = true)
        val gen = generation
        val started = SystemClock.elapsedRealtime()
        worker.execute {
            var improved = 0
            val parts = lines.values.map { ws ->
                val original = ws.joinToString(" ") { it.text }
                if (ws.none { it.fromImage }) return@map ws.first().para to original
                val region = lineRegion(ws, all)
                val reread = runCatching { OcrEngine.rereadLine(ctx, bmp, region) }.getOrNull()
                // Keep the careful reading unless it is clearly worse than the first one.
                val better = reread != null && reread.second >= 35 &&
                    reread.first.length >= (original.length * 0.6f)
                if (better && reread!!.first != original) improved++
                AppLog.i("re-read line: first ${AppLog.text(original)} → ${if (reread != null) AppLog.text(reread.first) + " (${reread.second}%)" else "none"}, kept ${if (better) "re-read" else "first"}")
                ws.first().para to (if (better) reread!!.first else original)
            }
            main.post {
                resolving = false
                if (destroyed || gen != generation) return@post
                AppLog.i("selection re-read: ${lines.size} lines, $improved changed, ${SystemClock.elapsedRealtime() - started} ms")
                setStatus(if (ocrRunning) busyStatus() else readyStatus(), busy = ocrRunning)
                val sb = StringBuilder()
                var lastPara = Int.MIN_VALUE
                for ((para, text) in parts) {
                    if (sb.isNotEmpty()) sb.append(if (para == lastPara) " " else "\n")
                    sb.append(text)
                    lastPara = para
                }
                action(sb.toString())
            }
        }
    }

    /**
     * The area to re-read for one selected line: the words' box widened by about half a letter
     * height on each side (to catch cut-off first or last letters), but never into the next
     * unselected word on the same line.
     */
    private fun lineRegion(ws: List<Word>, all: List<Word>): Rect {
        val box = RectF(ws.first().box)
        ws.forEach { box.union(it.box) }
        val h = box.height()
        var padLeft = 0.6f * h
        var padRight = 0.6f * h
        val lineId = ws.first().line
        for (o in all) {
            if (o.line != lineId || o in ws) continue
            if (o.box.right <= box.left) padLeft = minOf(padLeft, (box.left - o.box.right) * 0.7f)
            if (o.box.left >= box.right) padRight = minOf(padRight, (o.box.left - box.right) * 0.7f)
        }
        val padV = 0.25f * h
        return Rect(
            (box.left - padLeft).toInt(), (box.top - padV).toInt(),
            (box.right + padRight).toInt(), (box.bottom + padV).toInt()
        )
    }

    // ---------- Actions ----------

    /**
     * After text was copied: save it to the history, then either close (back to the app) or,
     * with "Stay to copy more" on, keep the frozen screen open for the next selection.
     */
    private fun afterCopy(text: String) {
        History.add(ctx, text)
        AppLog.i("copied ${AppLog.text(text)}")
        if (Prefs.stayAfterCopy(ctx)) {
            Toast.makeText(ctx, R.string.msg_copied_stay, Toast.LENGTH_SHORT).show()
            selectionView.clearSelection()
        } else {
            Toast.makeText(ctx, R.string.msg_copied, Toast.LENGTH_SHORT).show()
            host.close()
        }
    }

    private fun copy() = withSelectedText { text ->
        if (text.isBlank()) return@withSelectedText
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
        afterCopy(text)
    }

    private fun share() = withSelectedText { text ->
        if (text.isBlank()) return@withSelectedText
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            ctx.startActivity(
                Intent.createChooser(send, ctx.getString(R.string.share_text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLog.i("shared ${AppLog.text(text)}")
            host.close()
        } catch (e: Exception) {
            AppLog.e("share", e)
            Toast.makeText(ctx, R.string.msg_share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // Kept last: every field above must exist before the layout is built.
    init {
        buildLayout()
        root.isFocusableInTouchMode = true
    }
}
