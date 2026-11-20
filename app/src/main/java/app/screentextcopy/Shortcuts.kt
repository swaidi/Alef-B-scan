package app.screentextcopy

import android.app.ActivityOptions
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.InputType
import android.util.Patterns
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** Shows a dialog where the caller lives (settings screen or capture layer) and returns it. */
typealias ShowDialog = (MaterialAlertDialogBuilder) -> AlertDialog

/** One saved action that runs on the selected text. */
data class Shortcut(
    val type: String,
    val name: String,
    val value: String = "",
    val pkg: String = "",
    val cls: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("name", name).put("value", value).put("pkg", pkg).put("cls", cls)

    /** Built-in shortcuts show their name in the app's language unless the user renamed them. */
    fun displayName(c: Context): String {
        val builtIn = BUILT_IN_NAMES[type] ?: return name
        return if (name == builtIn.first) c.getString(builtIn.second) else name
    }

    fun describe(c: Context): String = when (type) {
        TYPE_SMART -> c.getString(R.string.sc_desc_smart)
        TYPE_TRANSLATE -> c.getString(R.string.sc_desc_translate)
        TYPE_PROCESS -> c.getString(R.string.sc_desc_process)
        TYPE_SHARE -> c.getString(R.string.sc_desc_share)
        TYPE_LINK -> c.getString(R.string.sc_desc_link, value)
        TYPE_NUMBERS -> c.getString(R.string.sc_desc_numbers)
        TYPE_CONTACTS -> c.getString(R.string.sc_desc_contacts)
        TYPE_JOIN -> c.getString(R.string.sc_desc_join)
        TYPE_TEMPLATE -> c.getString(R.string.sc_desc_template, value)
        else -> ""
    }

    companion object {
        const val TYPE_SMART = "smart"
        const val TYPE_TRANSLATE = "translate"
        const val TYPE_PROCESS = "process"
        const val TYPE_SHARE = "share"
        const val TYPE_LINK = "link"
        const val TYPE_NUMBERS = "numbers"
        const val TYPE_CONTACTS = "contacts"
        const val TYPE_JOIN = "join"
        const val TYPE_TEMPLATE = "template"

        /** English name stored for each built-in type, and its translatable label. */
        private val BUILT_IN_NAMES = mapOf(
            TYPE_SMART to ("Smart action" to R.string.sc_smart),
            TYPE_TRANSLATE to ("Translate" to R.string.sc_translate),
            TYPE_NUMBERS to ("Numbers only" to R.string.sc_numbers),
            TYPE_CONTACTS to ("Emails & phones" to R.string.sc_contacts),
            TYPE_JOIN to ("Join lines" to R.string.sc_join)
        )

        fun fromJson(o: JSONObject) = Shortcut(
            o.optString("type"), o.optString("name"), o.optString("value"),
            o.optString("pkg"), o.optString("cls")
        )
    }
}

/** Saved shortcuts and which one is pinned to the bar (shown as ★). */
object ShortcutStore {
    private const val KEY_LIST = "shortcuts"
    private const val KEY_PINNED = "shortcut_default"

    val SMART = Shortcut(Shortcut.TYPE_SMART, "Smart action")
    val TRANSLATE = Shortcut(Shortcut.TYPE_TRANSLATE, "Translate")
    val GOOGLE = Shortcut(Shortcut.TYPE_LINK, "Search Google", "https://www.google.com/search?q={text}")
    val IMDB = Shortcut(Shortcut.TYPE_LINK, "Search IMDb", "https://www.imdb.com/find/?q={text}")
    val NUMBERS = Shortcut(Shortcut.TYPE_NUMBERS, "Numbers only")
    val CONTACTS = Shortcut(Shortcut.TYPE_CONTACTS, "Emails & phones")
    val JOIN = Shortcut(Shortcut.TYPE_JOIN, "Join lines")

    /** First-run list: the smart action is first and pinned. */
    private val STARTER = listOf(SMART, TRANSLATE, GOOGLE, IMDB, NUMBERS, JOIN)

    fun load(c: Context): MutableList<Shortcut> {
        val raw = Prefs.get(c).getString(KEY_LIST, null) ?: return STARTER.toMutableList()
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { Shortcut.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            STARTER.toMutableList()
        }
    }

    fun save(c: Context, list: List<Shortcut>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        Prefs.get(c).edit().putString(KEY_LIST, a.toString()).apply()
    }

    fun pinnedIndex(c: Context, size: Int): Int =
        if (size <= 0) 0 else Prefs.get(c).getInt(KEY_PINNED, 0).coerceIn(0, size - 1)

    fun setPinned(c: Context, index: Int) {
        Prefs.get(c).edit().putInt(KEY_PINNED, index).apply()
    }

    fun pinned(c: Context): Shortcut? {
        val list = load(c)
        return if (list.isEmpty()) null else list[pinnedIndex(c, list.size)]
    }

    /** Removes one shortcut, keeping the pin on the same shortcut (or the first one if it was removed). */
    fun remove(c: Context, index: Int) {
        val list = load(c)
        if (index !in list.indices) return
        val pin = pinnedIndex(c, list.size)
        list.removeAt(index)
        save(c, list)
        when {
            index < pin -> setPinned(c, pin - 1)
            index == pin -> setPinned(c, 0)
        }
    }

    fun replace(c: Context, index: Int, s: Shortcut) {
        val list = load(c)
        if (index !in list.indices) return
        list[index] = s
        save(c, list)
    }

    fun moveUp(c: Context, index: Int) {
        val list = load(c)
        if (index <= 0 || index >= list.size) return
        val pin = pinnedIndex(c, list.size)
        list.add(index - 1, list.removeAt(index))
        save(c, list)
        when (pin) {
            index -> setPinned(c, index - 1)
            index - 1 -> setPinned(c, index)
        }
    }

    // ---------- Settings backup ----------

    fun exportJson(c: Context): JSONObject = JSONObject()
        .put("list", JSONArray().also { a -> load(c).forEach { a.put(it.toJson()) } })
        .put("pinned", pinnedIndex(c, load(c).size))

    fun importJson(c: Context, o: JSONObject) {
        val a = o.optJSONArray("list") ?: return
        save(c, (0 until a.length()).map { Shortcut.fromJson(a.getJSONObject(it)) })
        setPinned(c, o.optInt("pinned", 0))
    }
}

/** Runs shortcuts. Detection work happens off the main thread. */
object ShortcutRunner {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Runs [s] on [text]. [copied] is called after text was copied to the clipboard (for history and
     * "stay to copy more"); [done] is called when the capture screen should close; [other] opens the
     * list of shortcuts.
     */
    fun run(
        a: Context, host: CaptureHost, s: Shortcut, text: String,
        copied: (String) -> Unit, done: () -> Unit, other: () -> Unit
    ) {
        AppLog.i("shortcut: ${s.type} on ${AppLog.text(text)}")
        when (s.type) {
            Shortcut.TYPE_SMART -> smart(a, host, text, copied, done, other)
            Shortcut.TYPE_TRANSLATE -> translate(a, host, text, copied, done)
            Shortcut.TYPE_PROCESS -> launch(
                a,
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(s.pkg, s.cls)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
                done
            )
            Shortcut.TYPE_SHARE -> launch(
                a,
                Intent(Intent.ACTION_SEND).setType("text/plain").setClassName(s.pkg, s.cls)
                    .putExtra(Intent.EXTRA_TEXT, text),
                done
            )
            Shortcut.TYPE_LINK -> launch(a, Intent(Intent.ACTION_VIEW, Uri.parse(fillLink(s.value, text))), done)
            Shortcut.TYPE_NUMBERS -> copyResult(a, extractNumbers(text), copied)
            Shortcut.TYPE_CONTACTS -> copyResult(a, extractContacts(text), copied)
            Shortcut.TYPE_JOIN -> copyResult(a, text.replace(Regex("\\s*\\n\\s*"), " ").trim(), copied)
            Shortcut.TYPE_TEMPLATE -> copyResult(a, fillTemplate(s.value, text), copied)
            else -> Toast.makeText(a, R.string.msg_unknown_shortcut, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Helpers ----------

    fun launch(a: Context, intent: Intent, done: () -> Unit): Boolean {
        return try {
            a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            done()
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, R.string.msg_no_app, Toast.LENGTH_LONG).show()
            false
        } catch (e: SecurityException) {
            AppLog.w("launch refused: ${e.message}")
            Toast.makeText(a, R.string.msg_not_allowed, Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Copies [result]; [copied] decides whether the capture screen stays open. */
    fun copyResult(a: Context, result: String, copied: (String) -> Unit) {
        if (result.isBlank()) {
            Toast.makeText(a, R.string.msg_nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = a.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", result))
        copied(result)
    }

    private fun encode(text: String): String =
        URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

    fun fillLink(template: String, text: String): String =
        if (template.contains("{text}")) template.replace("{text}", encode(text)) else template + encode(text)

    private fun fillTemplate(template: String, text: String): String =
        if (template.contains("{text}")) template.replace("{text}", text) else template + text

    /** Converts Arabic-Indic and other digits to 0–9. */
    private fun asciiDigits(s: String): String = buildString {
        for (ch in s) {
            if (Character.isDigit(ch)) append(Character.getNumericValue(ch)) else append(ch)
        }
    }

    private fun extractNumbers(text: String): String =
        Regex("\\p{Nd}+(?:[-/.,:]\\p{Nd}+)*").findAll(text).map { it.value }.toList().joinToString("\n")

    private fun extractContacts(text: String): String {
        val found = LinkedHashSet<String>()
        val emails = Patterns.EMAIL_ADDRESS.matcher(text)
        while (emails.find()) found.add(emails.group())
        val phones = Patterns.PHONE.matcher(asciiDigits(text))
        while (phones.find()) {
            val p = phones.group().trim()
            if (p.count { it.isDigit() } >= 8) found.add(p)
        }
        return found.joinToString("\n")
    }

    // ---------- Translate ----------

    private fun translate(a: Context, host: CaptureHost, text: String, copied: (String) -> Unit, done: () -> Unit) {
        Toast.makeText(a, R.string.tr_working, Toast.LENGTH_SHORT).show()
        worker.execute {
            val available = SystemTranslator.available(a)
            if (!available) {
                main.post { if (host.alive) offerTranslatorApp(a, host, text, done) }
                return@execute
            }
            SystemTranslator.translate(a, text) { result ->
                main.post {
                    if (!host.alive) return@post
                    if (result.isNullOrBlank()) {
                        offerTranslatorApp(a, host, text, done)
                        return@post
                    }
                    host.show(
                        MaterialAlertDialogBuilder(a)
                            .setTitle(R.string.tr_title)
                            .setMessage(result)
                            .setPositiveButton(R.string.tr_copy) { _, _ -> copyResult(a, result, copied) }
                            .setNegativeButton(R.string.close, null)
                    )
                }
            }
        }
    }

    /** No built-in translation: use an installed translator app, or offer to install one. */
    private fun offerTranslatorApp(a: Context, host: CaptureHost, text: String, done: () -> Unit) {
        val apps = textActionApps(a).filter { (label, _) ->
            label.contains("transl", ignoreCase = true) || label.contains("ترجم") || label.contains("ترجمة")
        }.ifEmpty { textActionApps(a) }
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.tr_title)
            .setMessage(R.string.tr_unavailable)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.tr_install) { _, _ -> openStoreSearch(a, done) }
        if (apps.isNotEmpty()) {
            b.setPositiveButton(R.string.tr_use_app) { _, _ ->
                if (apps.size == 1) {
                    runTextAction(a, apps[0].second, text, done)
                } else {
                    host.show(
                        MaterialAlertDialogBuilder(a)
                            .setTitle(R.string.choose_text_action)
                            .setItems(apps.map { it.first }.toTypedArray()) { _, i -> runTextAction(a, apps[i].second, text, done) }
                            .setNegativeButton(R.string.cancel, null)
                    )
                }
            }
        }
        host.show(b)
    }

    private fun runTextAction(a: Context, component: Pair<String, String>, text: String, done: () -> Unit) {
        launch(
            a,
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(component.first, component.second)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            done
        )
    }

    /** Apps offering a text action, as (label, package to activity). */
    fun textActionApps(a: Context): List<Pair<String, Pair<String, String>>> {
        val pm = a.packageManager
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
            .filter { it.activityInfo.packageName != a.packageName }
            .map { info ->
                val action = info.loadLabel(pm).toString()
                val app = pm.getApplicationLabel(info.activityInfo.applicationInfo).toString()
                val label = if (action.equals(app, ignoreCase = true)) action else "$action — $app"
                label to (info.activityInfo.packageName to info.activityInfo.name)
            }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    /** Opens the phone's app store (F-Droid, Aurora, Play…) searching for an offline translator. */
    private fun openStoreSearch(a: Context, done: () -> Unit) {
        val ok = try {
            a.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=offline%20translator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            launch(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://search.f-droid.org/?q=translator")), {})
        }
        if (ok) done() else Toast.makeText(a, R.string.tr_no_store, Toast.LENGTH_LONG).show()
    }

    // ---------- Smart action ----------

    private class Found(val label: String, val run: () -> Unit)

    private fun smart(
        a: Context, host: CaptureHost, text: String,
        copied: (String) -> Unit, done: () -> Unit, other: () -> Unit
    ) {
        worker.execute {
            val found = try {
                detect(a, text, copied, done)
            } catch (e: Exception) {
                AppLog.e("smart detect", e)
                emptyList()
            }
            AppLog.i("smart action: ${found.size} actions found")
            main.post {
                if (!host.alive) return@post
                when {
                    found.isEmpty() -> host.show(
                        MaterialAlertDialogBuilder(a)
                            .setTitle(R.string.sc_smart)
                            .setMessage(R.string.smart_none)
                            .setPositiveButton(R.string.btn_other_shortcuts) { _, _ -> other() }
                            .setNegativeButton(R.string.cancel, null)
                    )
                    found.size == 1 -> found[0].run()
                    else -> host.show(
                        MaterialAlertDialogBuilder(a)
                            .setTitle(R.string.sc_smart)
                            .setItems(found.map { it.label }.toTypedArray()) { _, i -> found[i].run() }
                            .setNegativeButton(R.string.cancel, null)
                    )
                }
            }
        }
    }

    private class Entity(val value: String, val type: String, val start: Int, val end: Int)

    /** Finds entities with Android's on-device text classifier plus simple patterns. */
    private fun detect(c: Context, text: String, copied: (String) -> Unit, done: () -> Unit): List<Found> {
        val entities = ArrayList<Entity>()
        val seen = HashSet<String>()
        fun add(e: Entity) {
            if (seen.add(e.value)) entities.add(e)
        }

        var tc: TextClassifier? = null
        var classified = text
        try {
            tc = c.getSystemService(TextClassificationManager::class.java)?.textClassifier
            if (tc != null && tc !== TextClassifier.NO_OP) {
                val max = tc.maxGenerateLinksTextLength
                classified = if (text.length > max) text.substring(0, max) else text
                val links = tc.generateLinks(TextLinks.Request.Builder(classified).build())
                for (link in links.links) {
                    if (link.entityCount == 0) continue
                    val value = classified.substring(link.start, link.end).trim()
                    if (value.isNotEmpty()) add(Entity(value, link.getEntity(0), link.start, link.end))
                }
            }
        } catch (e: Exception) {
            AppLog.w("text classifier failed: ${e.message}")
        }

        // Simple patterns: always for IBANs and reference codes; for the rest only if nothing was found.
        val upper = asciiDigits(text).uppercase(Locale.ROOT)
        Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b").findAll(upper).forEach { m ->
            val iban = m.value.replace(" ", "")
            if (validIban(iban)) add(Entity(iban, TYPE_IBAN, -1, -1))
        }
        Regex("\\b[A-Z]{2,}[A-Z0-9]*(?:-[A-Z0-9]{1,12}){2,}\\b").findAll(upper).forEach { m ->
            if (!validIban(m.value)) add(Entity(m.value, TYPE_CODE, -1, -1))
        }
        if (entities.none { it.type != TYPE_IBAN && it.type != TYPE_CODE }) {
            val urls = Patterns.WEB_URL.matcher(text)
            while (urls.find()) {
                val v = urls.group()
                if (v.startsWith("http", ignoreCase = true) || v.startsWith("www.", ignoreCase = true)) {
                    add(Entity(v, TextClassifier.TYPE_URL, -1, -1))
                }
            }
            val emails = Patterns.EMAIL_ADDRESS.matcher(text)
            while (emails.find()) add(Entity(emails.group(), TextClassifier.TYPE_EMAIL, -1, -1))
            val phones = Patterns.PHONE.matcher(asciiDigits(text))
            while (phones.find()) {
                val p = phones.group().trim()
                if (p.count { it.isDigit() } >= 8) add(Entity(p, TextClassifier.TYPE_PHONE, -1, -1))
            }
        }

        val out = ArrayList<Found>()
        fun open(intent: Intent): () -> Unit = { launch(c, intent, done) }
        for (e in entities) {
            val v = e.value
            when (e.type) {
                TextClassifier.TYPE_PHONE -> {
                    val number = asciiDigits(v)
                    out.add(Found(c.getString(R.string.smart_call, v), open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))))
                    whatsappNumber(c, number)?.let {
                        out.add(Found(c.getString(R.string.smart_whatsapp, v), open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$it")))))
                    }
                }
                TextClassifier.TYPE_EMAIL ->
                    out.add(Found(c.getString(R.string.smart_email, v), open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(v))))))
                TextClassifier.TYPE_URL -> {
                    val url = if (v.contains("://")) v else "https://$v"
                    out.add(Found(c.getString(R.string.smart_open, v), open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))))
                }
                TextClassifier.TYPE_ADDRESS ->
                    out.add(Found(c.getString(R.string.smart_map, v), open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(v))))))
                TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME -> {
                    val action = dateAction(tc, classified, e)
                    if (action != null) {
                        out.add(Found(c.getString(R.string.smart_date, action.title, v)) { sendAction(c, action, done) })
                    }
                }
                TYPE_IBAN -> out.add(Found(c.getString(R.string.smart_iban, v)) { copyResult(c, v, copied) })
                TYPE_CODE -> out.add(Found(c.getString(R.string.smart_code, v)) { copyResult(c, v, copied) })
            }
            if (out.size >= 14) break
        }
        return out
    }

    private const val TYPE_IBAN = "iban"
    private const val TYPE_CODE = "code"

    /** The phone's own action for a date (usually "Add to calendar"), when it offers one. */
    private fun dateAction(tc: TextClassifier?, text: String, e: Entity): RemoteAction? {
        if (tc == null || e.start < 0) return null
        return try {
            tc.classifyText(TextClassification.Request.Builder(text, e.start, e.end).build()).actions.firstOrNull()
        } catch (ex: Exception) {
            AppLog.w("date action failed: ${ex.message}")
            null
        }
    }

    private fun sendAction(c: Context, action: RemoteAction, done: () -> Unit) {
        try {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            action.actionIntent.send(c, 0, null, null, null, null, opts.toBundle())
            done()
        } catch (e: Exception) {
            AppLog.e("date action send", e)
            Toast.makeText(c, R.string.msg_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** IBAN checksum (ISO 13616): avoids treating random codes as account numbers. */
    private fun validIban(s: String): Boolean {
        if (s.length !in 15..34 || !s[0].isLetter() || !s[1].isLetter()) return false
        val r = s.substring(4) + s.substring(0, 4)
        var rem = 0
        for (ch in r) {
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                else -> return false
            }
            rem = if (v >= 10) (rem * 100 + v) % 97 else (rem * 10 + v) % 97
        }
        return rem == 1
    }

    /** International number for wa.me links, using the SIM's country for local numbers. */
    private fun whatsappNumber(c: Context, number: String): String? {
        val iso = (c.getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "")
            .ifEmpty { Locale.getDefault().country }
            .uppercase(Locale.ROOT)
        val e164 = try {
            PhoneNumberUtils.formatNumberToE164(number, iso)
        } catch (_: Exception) {
            null
        } ?: number
        val digits = e164.filter { it in '0'..'9' }
        return digits.ifEmpty { null }
    }
}

/** Dialogs for adding, pinning, editing and removing shortcuts (settings screen and capture layer). */
object ShortcutEditor {

    fun showAdd(c: Context, show: ShowDialog, onChanged: () -> Unit) {
        val choices = listOf<Pair<Int, () -> Unit>>(
            R.string.add_smart to { add(c, ShortcutStore.SMART, onChanged) },
            R.string.add_translate to { add(c, ShortcutStore.TRANSLATE, onChanged) },
            R.string.add_process to {
                pickApp(c, show, Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), Shortcut.TYPE_PROCESS) { s ->
                    add(c, s, onChanged)
                }
            },
            R.string.add_share to {
                pickApp(c, show, Intent(Intent.ACTION_SEND).setType("text/plain"), Shortcut.TYPE_SHARE) { s ->
                    add(c, s, onChanged)
                }
            },
            R.string.add_google to { add(c, ShortcutStore.GOOGLE, onChanged) },
            R.string.add_imdb to { add(c, ShortcutStore.IMDB, onChanged) },
            R.string.add_custom_link to {
                askNameAndValue(c, show, c.getString(R.string.custom_link), "", "https://example.com/search?q={text}", true) { n, v ->
                    add(c, Shortcut(Shortcut.TYPE_LINK, n.ifEmpty { c.getString(R.string.my_search) }, v), onChanged)
                }
            },
            R.string.add_numbers to { add(c, ShortcutStore.NUMBERS, onChanged) },
            R.string.add_contacts to { add(c, ShortcutStore.CONTACTS, onChanged) },
            R.string.add_join to { add(c, ShortcutStore.JOIN, onChanged) },
            R.string.add_template to {
                askNameAndValue(c, show, c.getString(R.string.template_title), "", "Ref: {text}", true) { n, v ->
                    add(c, Shortcut(Shortcut.TYPE_TEMPLATE, n.ifEmpty { c.getString(R.string.reference) }, v), onChanged)
                }
            }
        )
        show(
            MaterialAlertDialogBuilder(c)
                .setTitle(R.string.add_title)
                .setItems(choices.map { c.getString(it.first) }.toTypedArray()) { _, i -> choices[i].second() }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    /** Pin / Edit / (Move up) / Remove for one shortcut. */
    fun showOptions(c: Context, show: ShowDialog, index: Int, withMoveUp: Boolean, onChanged: () -> Unit) {
        val list = ShortcutStore.load(c)
        val s = list.getOrNull(index) ?: return
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        actions.add(R.string.opt_pin to {
            ShortcutStore.setPinned(c, index)
            Toast.makeText(c, c.getString(R.string.msg_pinned, s.displayName(c)), Toast.LENGTH_SHORT).show()
            onChanged()
        })
        actions.add(R.string.opt_edit to { edit(c, show, index, onChanged) })
        if (withMoveUp && index > 0) actions.add(R.string.opt_move_up to { ShortcutStore.moveUp(c, index); onChanged() })
        actions.add(R.string.opt_remove to {
            ShortcutStore.remove(c, index)
            Toast.makeText(c, c.getString(R.string.msg_removed, s.displayName(c)), Toast.LENGTH_SHORT).show()
            onChanged()
        })
        show(
            MaterialAlertDialogBuilder(c)
                .setTitle(s.displayName(c))
                .setItems(actions.map { c.getString(it.first) }.toTypedArray()) { _, i -> actions[i].second() }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    /** Edit: name for every shortcut, the link or template where there is one, or another app. */
    private fun edit(c: Context, show: ShowDialog, index: Int, onChanged: () -> Unit) {
        val s = ShortcutStore.load(c).getOrNull(index) ?: return
        val hasValue = s.type == Shortcut.TYPE_LINK || s.type == Shortcut.TYPE_TEMPLATE
        val hasApp = s.type == Shortcut.TYPE_PROCESS || s.type == Shortcut.TYPE_SHARE
        askNameAndValue(
            c, show, c.getString(R.string.edit_title), s.displayName(c), s.value, hasValue,
            neutral = if (hasApp) c.getString(R.string.change_app) to {
                val intent = if (s.type == Shortcut.TYPE_PROCESS) Intent(Intent.ACTION_PROCESS_TEXT) else Intent(Intent.ACTION_SEND)
                pickApp(c, show, intent.setType("text/plain"), s.type) { picked ->
                    ShortcutStore.replace(c, index, picked.copy(name = s.name))
                    onChanged()
                }
            } else null
        ) { n, v ->
            ShortcutStore.replace(c, index, s.copy(name = n.ifEmpty { s.name }, value = if (hasValue) v else s.value))
            onChanged()
        }
    }

    private fun add(c: Context, s: Shortcut, onChanged: () -> Unit) {
        val list = ShortcutStore.load(c)
        list.add(s)
        ShortcutStore.save(c, list)
        Toast.makeText(c, c.getString(R.string.msg_added, s.displayName(c)), Toast.LENGTH_SHORT).show()
        onChanged()
    }

    private fun pickApp(c: Context, show: ShowDialog, intent: Intent, type: String, onPicked: (Shortcut) -> Unit) {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != c.packageName }
        if (infos.isEmpty()) {
            Toast.makeText(c, R.string.msg_no_apps, Toast.LENGTH_LONG).show()
            return
        }
        val entries = infos.map { info ->
            val action = info.loadLabel(pm).toString()
            val app = pm.getApplicationLabel(info.activityInfo.applicationInfo).toString()
            val label = if (action.equals(app, ignoreCase = true)) action else "$action — $app"
            Triple(label, action, info)
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        show(
            MaterialAlertDialogBuilder(c)
                .setTitle(if (type == Shortcut.TYPE_PROCESS) R.string.choose_text_action else R.string.share_to)
                .setItems(entries.map { it.first }.toTypedArray()) { _, i ->
                    val (_, action, info) = entries[i]
                    onPicked(Shortcut(type, action, "", info.activityInfo.packageName, info.activityInfo.name))
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun askNameAndValue(
        c: Context, show: ShowDialog, title: String, name: String, value: String, withValue: Boolean,
        neutral: Pair<String, () -> Unit>? = null, onOk: (String, String) -> Unit
    ) {
        val pad = c.dp(20)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameField = EditText(c).apply {
            hint = c.getString(R.string.name_label)
            setText(name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(nameField)
        val valueField = EditText(c).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (withValue) box.addView(valueField)
        val b = MaterialAlertDialogBuilder(c)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = valueField.text.toString().trim()
                if (!withValue || v.isNotEmpty()) onOk(nameField.text.toString().trim(), v)
            }
            .setNegativeButton(R.string.cancel, null)
        if (withValue) b.setMessage(R.string.placeholder_note)
        if (neutral != null) b.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
        show(b)
    }
}
