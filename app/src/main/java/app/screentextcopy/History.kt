package app.screentextcopy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Copy history, kept only on this phone. Items older than the chosen time are removed
 * automatically; the list can be cleared or turned off in settings.
 */
object History {
    private const val FILE = "copy-history.json"
    private const val MAX_ITEMS = 50

    data class Item(val text: String, val time: Long)

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    @Synchronized
    fun list(c: Context): List<Item> {
        val all = try {
            val a = JSONArray(file(c).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.getString("t"), it.getLong("at")) }
        } catch (_: Exception) {
            emptyList()
        }
        val hours = Prefs.historyHours(c)
        if (hours <= 0) return all
        val cutoff = System.currentTimeMillis() - hours * 3600_000L
        val kept = all.filter { it.time >= cutoff }
        if (kept.size != all.size) save(c, kept)
        return kept
    }

    @Synchronized
    fun add(c: Context, text: String) {
        if (!Prefs.historyOn(c) || text.isBlank()) return
        val items = listOf(Item(text, System.currentTimeMillis())) + list(c).filter { it.text != text }
        save(c, items.take(MAX_ITEMS))
    }

    @Synchronized
    fun remove(c: Context, item: Item) = save(c, list(c).filter { it != item })

    @Synchronized
    fun clear(c: Context) {
        try {
            file(c).delete()
        } catch (_: Exception) {
        }
    }

    private fun save(c: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("t", it.text).put("at", it.time)) }
        try {
            file(c).writeText(a.toString())
        } catch (e: Exception) {
            AppLog.e("history save", e)
        }
    }
}
