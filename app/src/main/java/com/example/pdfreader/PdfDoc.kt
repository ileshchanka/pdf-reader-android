package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Обёртка над системным [PdfRenderer].
 * PdfRenderer умеет держать открытой только одну страницу за раз, поэтому весь доступ идёт под [mutex].
 *
 * Обрезка полей: границы контента по горизонтали ищутся по пикселям уменьшенной копии страницы
 * ([probeBounds]). Для документа считается общая область по выборке страниц (отдельно для чётных
 * и нечётных), чтобы масштаб текста не прыгал; для каждой страницы область расширяется до её
 * собственного контента, так что ничего не обрезается.
 */
class PdfDoc private constructor(
    private val renderer: PdfRenderer,
    val name: String,
    private val pageWidths: IntArray,  // в пунктах (1/72")
    private val pageHeights: IntArray,
) {
    val pageCount: Int get() = pageWidths.size

    private val mutex = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Границы контента страницы [left, right] в долях ширины; left > right — пустая страница. */
    private val pageBounds = arrayOfNulls<FloatArray>(pageCount)

    /** Общая область обрезки для нечётных (индекс 0) и чётных (1) страниц; null — ещё не посчитана. */
    @Volatile
    private var docCrop: Array<FloatArray>? = null

    /** Растёт при появлении новых границ — Compose перечитывает пропорции и ключи страниц. */
    private val cropVersion = mutableIntStateOf(0)

    /** Область страницы [left, right] для показа с обрезкой, либо null, если обрезать пока нечем. */
    private fun cropRect(index: Int): FloatArray? {
        val doc = docCrop ?: return null
        val base = doc[if (pageCount >= 4) index % 2 else 0]
        val own = pageBounds[index] ?: return base
        if (own[0] > own[1]) return base
        return floatArrayOf(
            minOf(base[0], (own[0] - PADDING).coerceAtLeast(0f)),
            maxOf(base[1], (own[1] + PADDING).coerceAtMost(1f)),
        )
    }

    /** Отношение высоты к ширине страницы в том виде, как она будет показана. */
    fun displayAspect(index: Int, crop: Boolean): Float {
        cropVersion.intValue // подписка на обновления границ
        val full = pageHeights[index].toFloat() / pageWidths[index]
        val rect = if (crop) cropRect(index) else null
        return if (rect == null) full else full / (rect[1] - rect[0])
    }

    /** Ключ, однозначно описывающий, как будет нарисована страница (меняется при уточнении обрезки). */
    fun cropKey(index: Int, crop: Boolean): String {
        cropVersion.intValue
        return rectKey(if (crop) cropRect(index) else null)
    }

    private fun rectKey(rect: FloatArray?): String =
        if (rect == null) "full" else "%.4f-%.4f".format(Locale.ROOT, rect[0], rect[1])

    /** Готовый битмап из кэша без ожидания (или null). */
    fun peek(index: Int, width: Int, crop: Boolean): Bitmap? =
        cache.get("$index:$width:${cropKey(index, crop)}")

    suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val page = renderer.openPage(index)
            try {
                // Для обрезки нужны границы именно этой страницы — считаем их на уже открытой странице.
                if (crop && docCrop != null && pageBounds[index] == null) {
                    pageBounds[index] = probeBounds(page, index)
                    cropVersion.intValue++
                }
                val rect = if (crop) cropRect(index) else null
                val key = "$index:$width:${rectKey(rect)}"
                cache.get(key)?.let { return@withLock it }

                val left = rect?.get(0) ?: 0f
                val right = rect?.get(1) ?: 1f
                val cropWidthPt = pageWidths[index] * (right - left)
                var outWidth = width
                var scale = outWidth / cropWidthPt
                var outHeight = (pageHeights[index] * scale).roundToInt()
                if (outWidth.toLong() * outHeight > MAX_PIXELS) {
                    // Узкая обрезанная страница получается очень высокой — ограничиваем память.
                    val k = sqrt(MAX_PIXELS.toFloat() / (outWidth.toLong() * outHeight))
                    outWidth = (outWidth * k).toInt()
                    scale = outWidth / cropWidthPt
                    outHeight = (pageHeights[index] * scale).roundToInt()
                }
                val bitmap = Bitmap.createBitmap(outWidth, outHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-pageWidths[index] * left * scale, 0f)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    /** Считает общую область обрезки по выборке страниц. Повторные вызовы ничего не делают. */
    suspend fun prepareCrop() = withContext(Dispatchers.IO) {
        if (docCrop != null) return@withContext
        val sample = if (pageCount <= SAMPLE_SIZE) (0 until pageCount).toList()
        else List(SAMPLE_SIZE) { it * (pageCount - 1) / (SAMPLE_SIZE - 1) }
        for (index in sample) {
            mutex.withLock {
                if (closed) return@withContext
                if (pageBounds[index] == null) {
                    val page = renderer.openPage(index)
                    try {
                        pageBounds[index] = probeBounds(page, index)
                    } finally {
                        page.close()
                    }
                }
            }
        }
        val measured = sample.filter { pageBounds[it]!!.let { b -> b[0] <= b[1] } }
        fun areaOf(indices: List<Int>): FloatArray? {
            if (indices.isEmpty()) return null
            val lefts = indices.map { pageBounds[it]!![0] }.sorted()
            val rights = indices.map { pageBounds[it]!![1] }.sorted()
            return floatArrayOf(
                (lefts[(lefts.size * DOC_PERCENTILE).toInt()] - PADDING).coerceAtLeast(0f),
                (rights[((rights.size - 1) * (1f - DOC_PERCENTILE)).roundToInt()] + PADDING).coerceAtMost(1f),
            )
        }
        val all = areaOf(measured) ?: floatArrayOf(0f, 1f)
        docCrop = if (pageCount >= 4) {
            arrayOf(
                areaOf(measured.filter { it % 2 == 0 }) ?: all,
                areaOf(measured.filter { it % 2 == 1 }) ?: all,
            )
        } else {
            arrayOf(all, all)
        }
        cropVersion.intValue++
    }

    /**
     * Рисует страницу в маленький битмап и ищет горизонтальные границы контента.
     * С каждого края отбрасывается [EDGE_INK_SHARE] всех «чернил» — так соринки скана,
     * одинокий номер страницы или пометка на поле не мешают обрезке.
     */
    private fun probeBounds(page: PdfRenderer.Page, index: Int): FloatArray {
        val w = PROBE_WIDTH
        val h = (w * pageHeights[index].toFloat() / pageWidths[index]).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        val inkPerColumn = IntArray(w)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                val luma = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (luma < INK_THRESHOLD) inkPerColumn[x]++
            }
        }
        for (x in 0 until w) if (inkPerColumn[x] < MIN_INK_PER_COLUMN) inkPerColumn[x] = 0
        val total = inkPerColumn.sum()
        if (total == 0) return floatArrayOf(1f, 0f) // пустая страница
        val skip = total * EDGE_INK_SHARE
        var first = 0
        var acc = 0
        while (first < w - 1 && acc + inkPerColumn[first] <= skip) acc += inkPerColumn[first++]
        var last = w - 1
        acc = 0
        while (last > first && acc + inkPerColumn[last] <= skip) acc += inkPerColumn[last--]
        return floatArrayOf(first.toFloat() / w, (last + 1).toFloat() / w)
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
        private const val PROBE_WIDTH = 300
        private const val INK_THRESHOLD = 200
        private const val MIN_INK_PER_COLUMN = 2
        /** Доля «чернил», которую можно отбросить с каждого края страницы. */
        private const val EDGE_INK_SHARE = 0.005f
        /** Перцентиль левых/правых границ выборки для общей области документа. */
        private const val DOC_PERCENTILE = 0.25f
        private const val PADDING = 0.01f
        private const val SAMPLE_SIZE = 40
        private const val MAX_PIXELS = 6_000_000L

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
                val count = renderer.pageCount
                if (count == 0) throw IOException("Пустой документ")
                val widths = IntArray(count)
                val heights = IntArray(count)
                for (i in 0 until count) {
                    val page = renderer.openPage(i)
                    try {
                        widths[i] = page.width.coerceAtLeast(1)
                        heights[i] = page.height.coerceAtLeast(1)
                    } finally {
                        page.close()
                    }
                }
                PdfDoc(renderer, queryName(context, uri), widths, heights)
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
