package org.openwild.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A square JPEG small enough for a credit line. Re-encoding drops EXIF, including GPS. */
object AvatarImage {
    const val SIDE = 256

    fun fromUri(context: Context, uri: Uri): ByteArray? {
        val decoded = Contribute.decodeUpright(context, uri, 1024) ?: return null
        return jpeg(decoded).also { decoded.recycle() }
    }

    fun jpeg(source: Bitmap): ByteArray {
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = if (cropped.width == SIDE) cropped else Bitmap.createScaledBitmap(cropped, SIDE, SIDE, true)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== source) cropped.recycle()
        return bytes
    }
}
