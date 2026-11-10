package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

object Photos {
    /** Decodes a picked photo upright (EXIF rotation applied), at most [maxSide] px, as ARGB_8888. */
    fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            if (scale < 1f) dec.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }
}

/** The last head built on the face screen, so the body screen can put it on the body. */
object HeadStore {
    @Volatile var mesh: FaceMesh? = null
    @Volatile var texture: Bitmap? = null
}
