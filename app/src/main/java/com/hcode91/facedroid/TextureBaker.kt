package com.hcode91.facedroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

/** Copies each mesh triangle's pixels out of the photo into its slot on the UV layout. */
object TextureBaker {

    fun bake(photo: Bitmap, topo: FaceTopology, lm: FloatArray, size: Int = 2048): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(averageColour(photo, lm))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val src = FloatArray(6)
        val dst = FloatArray(6)
        val m = Matrix()
        val path = Path()
        val grow = 1.5f // px; slightly enlarge each triangle so neighbours overlap and no seams show

        for (t in 0 until topo.triCount) {
            for (j in 0 until 3) {
                val v = topo.tris[t * 3 + j]
                src[j * 2] = lm[v * 3]
                src[j * 2 + 1] = lm[v * 3 + 1]
                dst[j * 2] = topo.uv[v * 2] * size
                dst[j * 2 + 1] = (1f - topo.uv[v * 2 + 1]) * size
            }
            if (!m.setPolyToPoly(src, 0, dst, 0, 3)) continue

            val cx = (dst[0] + dst[2] + dst[4]) / 3f
            val cy = (dst[1] + dst[3] + dst[5]) / 3f
            path.rewind()
            for (j in 0 until 3) {
                val dx = dst[j * 2] - cx
                val dy = dst[j * 2 + 1] - cy
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val x = dst[j * 2] + dx / len * grow
                val y = dst[j * 2 + 1] + dy / len * grow
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(photo, m, paint)
            canvas.restore()
        }
        return out
    }

    /** Average skin colour, used to fill the unused parts of the UV map. */
    private fun averageColour(photo: Bitmap, lm: FloatArray): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in 0 until FaceTopology.N) {
            val x = lm[i * 3].toInt(); val y = lm[i * 3 + 1].toInt()
            if (x !in 0 until photo.width || y !in 0 until photo.height) continue
            val c = photo.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return Color.GRAY
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
