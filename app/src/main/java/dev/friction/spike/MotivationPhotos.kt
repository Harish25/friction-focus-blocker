package dev.friction.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode bounded thumbnails off the UI thread; inaccessible media always yields the text fallback. */
object MotivationPhotos {
    private var index = 0
    fun next(photos: List<String>): String? = if (photos.isEmpty()) null else photos[(index++ and Int.MAX_VALUE) % photos.size]
    suspend fun load(context: Context, reference: String?): Bitmap? = withContext(Dispatchers.IO) {
        loadOrFallback(reference) { uri ->
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
                val scale = minOf(1.0, 900.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }
}

internal suspend fun <T> loadOrFallback(reference: String?, decode: suspend (String) -> T): T? {
    if (reference == null) return null
    return try { decode(reference) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
}
