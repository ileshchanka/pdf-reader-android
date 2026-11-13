package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Обёртка над системным [PdfRenderer].
 * PdfRenderer умеет держать открытой только одну страницу за раз, поэтому весь доступ идёт под [mutex].
 */
class PdfDoc private constructor(
    private val renderer: PdfRenderer,
    val name: String,
    /** Отношение высоты страницы к ширине — нужно для плейсхолдеров до отрисовки. */
    private val aspects: FloatArray,
) {
    val pageCount: Int get() = aspects.size

    fun aspect(index: Int): Float = aspects[index]

    private val mutex = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun key(index: Int, width: Int) = "$index:$width"

    /** Готовый битмап из кэша без ожидания (или null). */
    fun peek(index: Int, width: Int): Bitmap? = cache.get(key(index, width))

    suspend fun render(index: Int, width: Int): Bitmap? {
        val key = key(index, width)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                val page = renderer.openPage(index)
                try {
                    val height = (width * aspects[index]).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                } finally {
                    page.close()
                }
            }
        }
    }

    /** Закрывает документ, дождавшись окончания текущей отрисовки. */
    fun close() {
        closeScope.launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    renderer.close() // закрывает и файловый дескриптор
                    cache.evictAll()
                }
            }
        }
    }

    companion object {
        private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun cacheSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        suspend fun open(context: Context, uri: Uri): PdfDoc = withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Не удалось открыть $uri")
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Throwable) {
                pfd.close()
                throw e
            }
            try {
                if (renderer.pageCount == 0) throw IOException("Пустой документ")
                val aspects = FloatArray(renderer.pageCount) { i ->
                    val page = renderer.openPage(i)
                    try {
                        if (page.width > 0) page.height.toFloat() / page.width else 1.414f
                    } finally {
                        page.close()
                    }
                }
                PdfDoc(renderer, queryName(context, uri), aspects)
            } catch (e: Throwable) {
                renderer.close()
                throw e
            }
        }

        private fun queryName(context: Context, uri: Uri): String {
            val fromProvider = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
        }
    }
}
