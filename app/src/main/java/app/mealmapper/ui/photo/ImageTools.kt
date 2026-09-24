package app.mealmapper.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Loads a photo (camera or gallery), fixes rotation, shrinks it, and returns JPEG bytes for the AI (Gemini limits a request to 20 MB; these are well under). */
object ImageTools {
    private const val MAX_SIDE = 1600
    private const val QUALITY = 85

    fun loadBitmap(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applies EXIF rotation, so sideways camera photos come out upright.
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun jpeg(context: Context, uri: Uri): ByteArray {
        val bitmap = loadBitmap(context, uri)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }
}
