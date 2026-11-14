package com.example.pdfreader

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: Uri, val name: String, val pageCount: Int)

/** Простое хранилище настроек: недавние файлы, страница для каждого файла, ночной режим. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun page(uri: Uri): Int = sp.getInt(pageKey(uri), 0)

    fun savePage(uri: Uri, page: Int) = sp.edit { putInt(pageKey(uri), page) }

    /** Недавние файлы, последний открытый — первым. */
    fun recentFiles(): List<RecentFile> {
        migrateLastFile()
        val json = sp.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                RecentFile(Uri.parse(o.getString("uri")), o.getString("name"), o.optInt("pages", 0))
            }
        }.getOrDefault(emptyList())
    }

    /** Поднимает файл в начало списка (или добавляет). */
    fun addRecent(file: RecentFile) {
        val list = listOf(file) + recentFiles().filter { it.uri != file.uri }
        val dropped = list.drop(MAX_RECENT)
        saveRecent(list.take(MAX_RECENT))
        sp.edit { dropped.forEach { remove(pageKey(it.uri)) } }
    }

    fun removeRecent(uri: Uri) {
        saveRecent(recentFiles().filter { it.uri != uri })
        sp.edit { remove(pageKey(uri)) }
    }

    var nightMode: Boolean
        get() = sp.getBoolean("night_mode", false)
        set(value) = sp.edit { putBoolean("night_mode", value) }

    private fun saveRecent(list: List<RecentFile>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("pages", it.pageCount))
        }
        sp.edit { putString(KEY_RECENT, array.toString()) }
    }

    /** Первая версия хранила только один последний файл — переносим его в список. */
    private fun migrateLastFile() {
        val uri = sp.getString("last_uri", null) ?: return
        val name = sp.getString("last_name", null) ?: "PDF"
        sp.edit { remove("last_uri"); remove("last_name") }
        if (sp.getString(KEY_RECENT, null) == null) saveRecent(listOf(RecentFile(Uri.parse(uri), name, 0)))
    }

    private fun pageKey(uri: Uri) = "page:$uri"

    private companion object {
        const val KEY_RECENT = "recent_files"
        const val MAX_RECENT = 20
    }
}
