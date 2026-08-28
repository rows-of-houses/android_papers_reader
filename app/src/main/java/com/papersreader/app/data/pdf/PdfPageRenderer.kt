package com.papersreader.app.data.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders PDF pages to bitmaps using the framework's [PdfRenderer] (backed by Google's own
 * Pdfium natively) rather than pulling in a third-party rendering library. One renderer is
 * kept open per paper for the life of the reader screen; [PdfRenderer] itself is not
 * thread-safe so all access is serialized through [mutex].
 */
class PdfPageRenderer(file: File) : AutoCloseable {

    private val mutex = Mutex()
    private val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fileDescriptor)
    private val bitmapCache = LruCache<Int, Bitmap>(CACHE_SIZE)

    val pageCount: Int get() = renderer.pageCount

    suspend fun pageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        mutex.withLock {
            renderer.openPage(pageIndex).use { page -> page.width.toFloat() / page.height.toFloat() }
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = pageIndex * 10_000 + targetWidthPx / 10
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        mutex.withLock {
            bitmapCache.get(cacheKey)?.let { return@withLock it }
            renderer.openPage(pageIndex).use { page ->
                val width = targetWidthPx.coerceAtLeast(1)
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                // PdfRenderer.Page.render() requires ARGB_8888 specifically — any other config
                // throws IllegalArgumentException, so there's no cheaper format to render into.
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(cacheKey, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        bitmapCache.evictAll()
        renderer.close()
        fileDescriptor.close()
    }

    companion object {
        private const val CACHE_SIZE = 6
    }
}
