package app.screentextcopy

import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File

private const val MENU_UPDATE = 101
private const val MENU_REPORT = 102

/** Home: a toolbar (Update, Report) and simple setting groups, each opening its own page. */
class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout

    /** The open group page, or null for the home list. */
    private var page: String? = null
    private var scrollY = 0

    private val groups = listOf(
        "trigger" to (R.string.g_trigger to R.string.g_trigger_line),
        "shortcuts" to (R.string.g_shortcuts to R.string.g_shortcuts_line),
        "copy" to (R.string.g_copy to R.string.g_copy_line),
        "recognition" to (R.string.g_recognition to R.string.g_recognition_line),
        "tiles" to (R.string.g_tiles to R.string.g_tiles_line),
        "backup" to (R.string.g_backup to R.string.g_backup_line),
        "updates" to (R.string.g_updates to R.string.g_updates_line),
        "report" to (R.string.g_report to R.string.g_report_line),
    )

    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) Backup.export(this, uri)
    }
    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && Backup.import(this, uri)) refresh()
    }

    private val showDialog: ShowDialog = { b -> b.show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.i("app opened ${BuildConfig.VERSION_NAME}")

        val root = vbox()
        toolbar = MaterialToolbar(this).apply { setTitle(R.string.app_name) }
        toolbar.menu.add(0, MENU_UPDATE, 0, R.string.btn_update).setIcon(R.drawable.ic_update)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_REPORT, 1, R.string.g_report).setIcon(R.drawable.ic_report)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_UPDATE -> Updater.runFlow(this)
                MENU_REPORT -> open("report")
            }
            true
        }
        toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        content = FrameLayout(this)
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        page = savedInstanceState?.getString("page")
        refreshUpdateIcon()
        Updater.backgroundCheck(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun open(p: String?) {
        page = p
        scrollY = 0
        refresh()
    }

    private fun goBack(): Boolean {
        if (page == null) return false
        open(null)
        return true
    }

    /** Rebuilds the current page, keeping the scroll position. */
    fun refresh() {
        val old = content.getChildAt(0) as? ScrollView
        if (old != null) scrollY = old.scrollY
        content.removeAllViews()
        val title = groups.firstOrNull { it.first == page }?.second?.first
        toolbar.title = if (title != null) getString(title) else getString(R.string.app_name)
        toolbar.navigationIcon = if (page != null) {
            ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        } else null
        val body = vbox(16)
        when (page) {
            null -> homePage(body)
            "trigger" -> triggerPage(body)
            "shortcuts" -> shortcutsPage(body)
            "copy" -> copyPage(body)
            "recognition" -> recognitionPage(body)
            "tiles" -> tilesPage(body)
            "backup" -> backupPage(body)
            "updates" -> updatesPage(body)
            "report" -> reportPage(body)
        }
        val sv = scroll(body)
        content.addView(sv)
        val y = scrollY
        sv.post { sv.scrollTo(0, y) }
    }

    /** Shows a dot on the Update button when a newer version is available. */
    fun refreshUpdateIcon() {
        val item = toolbar.menu.findItem(MENU_UPDATE) ?: return
        val tag = Prefs.updateTag(this)
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_update)!!.mutate()
        if (tag != null) {
            val dot = ShapeDrawable(OvalShape()).apply {
                paint.color = Ui.ALERT
                intrinsicWidth = dp(9)
                intrinsicHeight = dp(9)
            }
            item.icon = LayerDrawable(arrayOf(icon, dot)).apply {
                setLayerGravity(1, Gravity.TOP or Gravity.END)
                setLayerSize(1, dp(9), dp(9))
            }
            item.title = getString(R.string.update_available, tag)
        } else {
            item.icon = icon
            item.title = getString(R.string.btn_update)
        }
    }

    // ---------- Home ----------

    private fun homePage(root: LinearLayout) {
        // Trigger status first: the one thing that must be on for the app to work.
        val on = TriggerService.instance != null
        root.addView(card(
            text(getString(if (on) R.string.status_on else R.string.status_off), 16f, true),
            muted(getString(if (on) R.string.status_on_line else R.string.status_off_line)),
            button(getString(R.string.open_accessibility), outlined = on) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ))
        for ((key, res) in groups) {
            root.addView(card(text(getString(res.first), 16f, true), muted(getString(res.second)), onClick = { open(key) }))
        }
        root.addView(section(getString(R.string.s_language)))
        val lang = chips()
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ar" to R.string.lang_ar).forEach { (tag, label) ->
            lang.addView(chip(getString(label), cur == tag) {
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
            })
        }
        root.addView(lang)
        root.addView(muted(getString(R.string.about, BuildConfig.VERSION_NAME), 12f).apply {
            setPadding(0, dp(24), 0, dp(24))
        })
    }

    // ---------- Trigger ----------

    private fun triggerPage(root: LinearLayout) {
        val p = Prefs.get(this)
        root.addView(muted(getString(R.string.restricted_hint), 12f))
        root.addView(button(getString(R.string.open_accessibility), outlined = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(section(getString(R.string.s_gesture)))
        root.addView(switchRow(getString(R.string.strip_enabled), Prefs.stripEnabled(this)) {
            p.edit().putBoolean(Prefs.KEY_STRIP_ENABLED, it).apply()
        })
        val mode = chips()
        listOf(
            Prefs.TRIGGER_LONG to R.string.trigger_long,
            Prefs.TRIGGER_DOUBLE to R.string.trigger_double,
            Prefs.TRIGGER_BOTH to R.string.trigger_both
        ).forEach { (key, label) ->
            mode.addView(chip(getString(label), Prefs.triggerMode(this) == key) {
                p.edit().putString(Prefs.KEY_TRIGGER_MODE, key).apply()
            })
        }
        root.addView(mode)

        root.addView(section(getString(R.string.s_strip)))
        root.addView(switchRow(getString(R.string.strip_visible), Prefs.stripVisible(this)) {
            p.edit().putBoolean(Prefs.KEY_STRIP_VISIBLE, it).apply()
        })
        root.addView(slider(getString(R.string.strip_height), Prefs.stripHeightDp(this), 8, 40, "dp") {
            p.edit().putInt(Prefs.KEY_STRIP_HEIGHT_DP, it).apply()
        })
        root.addView(slider(getString(R.string.strip_offset), Prefs.stripOffsetDp(this), 0, 80, "dp") {
            p.edit().putInt(Prefs.KEY_STRIP_OFFSET_DP, it).apply()
        })
        root.addView(slider(getString(R.string.strip_width), Prefs.stripWidthPct(this), 20, 100, "%") {
            p.edit().putInt(Prefs.KEY_STRIP_WIDTH_PCT, it).apply()
        })
        root.addView(muted(getString(R.string.strip_hint), 12f))
        root.addView(button(getString(R.string.test_capture), outlined = true) { testCapture() })
    }

    private fun testCapture() {
        val service = TriggerService.instance
        if (service != null) {
            moveTaskToBack(true)
            content.postDelayed({ service.captureNow() }, 500)
        } else {
            startActivity(Intent(this, ProjectionActivity::class.java))
        }
    }

    // ---------- Shortcuts ----------

    private fun shortcutsPage(root: LinearLayout) {
        root.addView(muted(getString(R.string.shortcuts_intro), 13f))
        val list = ShortcutStore.load(this)
        val pin = ShortcutStore.pinnedIndex(this, list.size)
        if (list.isEmpty()) root.addView(muted(getString(R.string.no_shortcuts)))
        list.forEachIndexed { i, s ->
            val name = if (i == pin) "★  ${s.displayName(this)}" else s.displayName(this)
            root.addView(card(text(name, 16f, i == pin), muted(s.describe(this)), onClick = {
                ShortcutEditor.showOptions(this, showDialog, i, withMoveUp = true) { refresh() }
            }))
        }
        root.addView(button(getString(R.string.add_title)) { ShortcutEditor.showAdd(this, showDialog) { refresh() } })
    }

    // ---------- Copy & history ----------

    private fun copyPage(root: LinearLayout) {
        val p = Prefs.get(this)
        root.addView(switchRow(getString(R.string.stay_after_copy), Prefs.stayAfterCopy(this)) {
            p.edit().putBoolean(Prefs.KEY_STAY_AFTER_COPY, it).apply()
        })
        root.addView(muted(getString(R.string.stay_after_copy_line), 12f))

        root.addView(section(getString(R.string.s_history)))
        root.addView(switchRow(getString(R.string.history_on), Prefs.historyOn(this)) {
            p.edit().putBoolean(Prefs.KEY_HISTORY_ON, it).apply()
            if (!it) History.clear(this)
            refresh()
        })
        root.addView(muted(getString(R.string.history_keep), 13f))
        val keep = chips()
        listOf(1 to R.string.keep_1h, 24 to R.string.keep_24h, 168 to R.string.keep_7d, 0 to R.string.keep_forever)
            .forEach { (h, label) ->
                keep.addView(chip(getString(label), Prefs.historyHours(this) == h) {
                    p.edit().putInt(Prefs.KEY_HISTORY_HOURS, h).apply()
                    refresh()
                })
            }
        root.addView(keep)
        root.addView(muted(getString(R.string.history_privacy), 12f))

        val items = History.list(this)
        if (items.isEmpty()) {
            root.addView(muted(getString(R.string.history_empty)).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            root.addView(section(getString(R.string.history_count, items.size)))
            items.forEach { item ->
                val age = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                root.addView(card(
                    text(item.text.take(300), 15f).apply { maxLines = 4 },
                    muted(age, 12f),
                    onClick = {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Screen text", item.text))
                        toast(getString(R.string.msg_copied))
                    },
                    onLongClick = {
                        confirm(getString(R.string.history_delete_q), item.text.take(120), getString(R.string.opt_remove)) {
                            History.remove(this, item)
                            refresh()
                        }
                    }
                ))
            }
            root.addView(muted(getString(R.string.history_tip), 12f))
            root.addView(button(getString(R.string.history_clear), outlined = true) {
                confirm(getString(R.string.history_clear), getString(R.string.history_clear_q), getString(R.string.history_clear)) {
                    History.clear(this)
                    refresh()
                }
            })
        }
    }

    // ---------- Recognition ----------

    private fun recognitionPage(root: LinearLayout) {
        val p = Prefs.get(this)
        root.addView(switchRow(getString(R.string.ocr_arabic), Prefs.arabic(this)) {
            p.edit().putBoolean(Prefs.KEY_ARABIC, it).apply()
        })
        root.addView(muted(getString(R.string.recognition_line), 12f))
    }

    // ---------- Quick Settings tiles ----------

    private fun tilesPage(root: LinearLayout) {
        root.addView(switchRow(getString(R.string.tile_toggle_setting), Prefs.isComponentEnabled(this, ToggleTileService::class.java)) {
            Prefs.setComponentEnabled(this, ToggleTileService::class.java, it)
        })
        root.addView(switchRow(getString(R.string.tile_capture_setting), Prefs.isComponentEnabled(this, CaptureTileService::class.java)) {
            Prefs.setComponentEnabled(this, CaptureTileService::class.java, it)
        })
        root.addView(muted(getString(R.string.tiles_hint), 12f))
    }

    // ---------- Backup ----------

    private fun backupPage(root: LinearLayout) {
        root.addView(muted(getString(R.string.backup_line), 13f))
        root.addView(button(getString(R.string.backup_export)) { exportSettings.launch("Alef_B_scan-settings.json") })
        root.addView(button(getString(R.string.backup_import), outlined = true) { importSettings.launch(arrayOf("application/json", "*/*")) })
    }

    // ---------- Updates ----------

    private fun updatesPage(root: LinearLayout) {
        val tag = Prefs.updateTag(this)
        root.addView(card(
            text(getString(R.string.version_line, BuildConfig.VERSION_NAME), 16f, true),
            muted(if (tag != null) getString(R.string.update_available, tag) else getString(R.string.updates_line)),
            button(getString(R.string.check_updates)) { Updater.runFlow(this) }
        ))
        root.addView(muted(getString(R.string.updates_internet_note), 12f))
    }

    // ---------- Test report ----------

    private fun reportPage(root: LinearLayout) {
        root.addView(muted(getString(R.string.report_intro), 13f))
        root.addView(switchRow(getString(R.string.report_include_text), Prefs.logText(this)) {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_LOG_TEXT, it).apply()
            AppLog.includeText = it
        })
        root.addView(muted(getString(R.string.report_include_text_line), 12f))
        root.addView(button(getString(R.string.report_view)) { viewReport() })
        root.addView(button(getString(R.string.report_share), outlined = true) { shareReport() })
        root.addView(button(getString(R.string.report_clear), outlined = true) {
            confirm(getString(R.string.report_clear), getString(R.string.report_clear_q), getString(R.string.report_clear)) {
                AppLog.clear(this)
                toast(getString(R.string.report_cleared))
            }
        })
    }

    private fun viewReport() {
        val report = AppLog.read(this)
        val tv = text(report, 12f).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(8))
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val d: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.g_report)
            .setView(sv)
            .setPositiveButton(R.string.report_share) { _, _ -> shareReport() }
            .setNegativeButton(R.string.close, null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        d.setCanceledOnTouchOutside(true)
    }

    private fun shareReport() {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, "Alef_B_scan-report.txt")
            file.writeText(AppLog.read(this))
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Alef_B_scan ${BuildConfig.VERSION_NAME} report")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.report_share)))
        } catch (e: Exception) {
            AppLog.e("share report", e)
            toast(getString(R.string.msg_share_unavailable))
        }
    }
}

/** Export and import of settings and shortcuts as a small JSON file (copy history is not included). */
object Backup {
    private val BOOL_KEYS = listOf(
        Prefs.KEY_STRIP_ENABLED, Prefs.KEY_STRIP_VISIBLE, Prefs.KEY_ARABIC,
        Prefs.KEY_STAY_AFTER_COPY, Prefs.KEY_HISTORY_ON, Prefs.KEY_LOG_TEXT
    )
    private val INT_KEYS = listOf(
        Prefs.KEY_STRIP_HEIGHT_DP, Prefs.KEY_STRIP_OFFSET_DP, Prefs.KEY_STRIP_WIDTH_PCT, Prefs.KEY_HISTORY_HOURS
    )
    private val STRING_KEYS = listOf(Prefs.KEY_TRIGGER_MODE)

    fun export(a: MainActivity, uri: Uri) {
        try {
            val p = Prefs.get(a)
            val settings = JSONObject()
            BOOL_KEYS.filter { p.contains(it) }.forEach { settings.put(it, p.getBoolean(it, false)) }
            INT_KEYS.filter { p.contains(it) }.forEach { settings.put(it, p.getInt(it, 0)) }
            STRING_KEYS.filter { p.contains(it) }.forEach { settings.put(it, p.getString(it, "")) }
            val o = JSONObject()
                .put("app", "Alef_B_scan")
                .put("version", BuildConfig.VERSION_NAME)
                .put("settings", settings)
                .put("shortcuts", ShortcutStore.exportJson(a))
            a.contentResolver.openOutputStream(uri)?.use { it.write(o.toString(2).toByteArray()) }
            AppLog.i("settings exported")
            a.toast(a.getString(R.string.backup_exported))
        } catch (e: Exception) {
            AppLog.e("settings export", e)
            a.toast(a.getString(R.string.backup_failed))
        }
    }

    fun import(a: MainActivity, uri: Uri): Boolean = try {
        val raw = a.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val o = JSONObject(raw)
        val s = o.optJSONObject("settings") ?: JSONObject()
        val e = Prefs.get(a).edit()
        BOOL_KEYS.filter { s.has(it) }.forEach { e.putBoolean(it, s.getBoolean(it)) }
        INT_KEYS.filter { s.has(it) }.forEach { e.putInt(it, s.getInt(it)) }
        STRING_KEYS.filter { s.has(it) }.forEach { e.putString(it, s.getString(it)) }
        e.apply()
        o.optJSONObject("shortcuts")?.let { ShortcutStore.importJson(a, it) }
        AppLog.includeText = Prefs.logText(a)
        AppLog.i("settings imported")
        a.toast(a.getString(R.string.backup_imported))
        true
    } catch (e: Exception) {
        AppLog.e("settings import", e)
        a.toast(a.getString(R.string.backup_failed))
        false
    }
}
