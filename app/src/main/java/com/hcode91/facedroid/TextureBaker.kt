package com.hcode91.facedroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sqrt

/** Copies each triangle's pixels out of the photo into its slot on the UV layout. */
object TextureBaker {

    fun bake(photo: Bitmap, mesh: FaceMesh): Bitmap {
        val w = mesh.texWidth
        val h = mesh.texHeight
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val skin = averageSkin(photo, mesh)
        canvas.drawColor(skin)
        if (mesh.fullHead) {
            // Right half holds the rest of the head; parts the photo can't see get the hair/scalp colour.
            val hair = patchColour(photo, mesh.hairSampleX, mesh.hairSampleY, 6) ?: skin
            canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = hair })
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val src = FloatArray(6)
        val dst = FloatArray(6)
        val m = Matrix()
        val path = Path()
        val grow = 1.5f // px; slightly enlarge each triangle so neighbours overlap and no seams show

        for (t in 0 until mesh.triCount) {
            if (!mesh.bakeFromPhoto[t]) continue
            var inside = true
            for (j in 0 until 3) {
                val v = mesh.tris[t * 3 + j]
                src[j * 2] = mesh.srcPx[v * 2]
                src[j * 2 + 1] = mesh.srcPx[v * 2 + 1]
                dst[j * 2] = mesh.uvs[v * 2] * w
                dst[j * 2 + 1] = (1f - mesh.uvs[v * 2 + 1]) * h
                if (src[j * 2] < 0 || src[j * 2 + 1] < 0 || src[j * 2] >= photo.width || src[j * 2 + 1] >= photo.height) inside = false
            }
            // Face triangles always bake (edge pixels just clamp); skull triangles need to be fully on the photo.
            if (!inside && t >= FACE_TRIS) continue
            if (!m.setPolyToPoly(src, 0, dst, 0, 3)) continue

            val cx = (dst[0] + dst[2] + dst[4]) / 3f
            val cy = (dst[1] + dst[3] + dst[5]) / 3f
            path.rewind()
            for (j in 0 until 3) {
                val dx = dst[j * 2] - cx
                val dy = dst[j * 2 + 1] - cy
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
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

    private const val FACE_TRIS = 898

    private fun averageSkin(photo: Bitmap, mesh: FaceMesh): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in 0 until FaceTopology.N) {
            val x = mesh.srcPx[i * 2].toInt(); val y = mesh.srcPx[i * 2 + 1].toInt()
            if (x !in 0 until photo.width || y !in 0 until photo.height) continue
            val c = photo.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return Color.GRAY
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun patchColour(photo: Bitmap, px: Float, py: Float, radius: Int): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in py.toInt() - radius..py.toInt() + radius) for (x in px.toInt() - radius..px.toInt() + radius) {
            if (x !in 0 until photo.width || y !in 0 until photo.height) continue
            val c = photo.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
