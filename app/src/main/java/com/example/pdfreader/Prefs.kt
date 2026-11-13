package com.example.pdfreader

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

data class LastFile(val uri: Uri, val name: String)

/** Простое хранилище настроек: последний файл, страница для каждого файла, ночной режим. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun page(uri: Uri): Int = sp.getInt("page:$uri", 0)

    fun savePage(uri: Uri, page: Int) = sp.edit { putInt("page:$uri", page) }

    fun lastFile(): LastFile? {
        val uri = sp.getString("last_uri", null) ?: return null
        return LastFile(Uri.parse(uri), sp.getString("last_name", null) ?: "PDF")
    }

    fun setLastFile(uri: Uri, name: String) = sp.edit {
        putString("last_uri", uri.toString())
        putString("last_name", name)
    }

    fun clearLastFile() = sp.edit { remove("last_uri"); remove("last_name") }

    var nightMode: Boolean
        get() = sp.getBoolean("night_mode", false)
        set(value) = sp.edit { putBoolean("night_mode", value) }
}
