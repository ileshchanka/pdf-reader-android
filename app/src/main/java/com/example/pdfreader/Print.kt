package com.example.pdfreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Отправляет PDF в системную печать. Файл передаётся как есть (без перерисовки),
 * поэтому печатается в исходном качестве. Нужен контекст Activity.
 */
fun printPdf(context: Context, uri: Uri, name: String, pageCount: Int) {
    val activity = context.findActivity()
    val manager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "PDF" }
    runCatching { manager.print(jobName, PdfPrintAdapter(activity.applicationContext, uri, name, pageCount), null) }
}

private class PdfPrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val name: String,
    private val pageCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        // Содержимое не зависит от параметров печати — перевыкладка не нужна.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // Копирование может быть долгим (облачные провайдеры), поэтому не на главном потоке.
        thread(name = "pdf-print") {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot open $uri")
                input.use { inp ->
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal.isCanceled) {
                                callback.onWriteCancelled()
                                return@thread
                            }
                            val n = inp.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(context.getString(R.string.error_open))
            }
        }
    }
}
