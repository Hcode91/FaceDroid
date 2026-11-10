package com.hcode91.facedroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Bakes the head/face texture from the front photo and, if given, a side photo. */
object TextureBaker {

    fun bake(photo: Bitmap, mesh: FaceMesh, sidePhoto: Bitmap? = null): Bitmap {
        val w = mesh.texWidth
        val h = mesh.texHeight
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val skin = averageSkin(photo, mesh)
        canvas.drawColor(skin)
        if (mesh.fullHead) {
            // Right half holds the rest of the head; parts no photo can see get the hair/scalp colour.
            val hair = Baker.patchColour(photo, mesh.hairSampleX, mesh.hairSampleY, 6) ?: skin
            canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = hair })
        }

        val views = ArrayList<Baker.View>()
        views.add(Baker.View(photo, mesh.srcPx))
        val sidePx = mesh.sidePx
        if (sidePhoto != null && sidePx != null) {
            // Match the side photo's colours to the front photo using the cheekbone (landmark 50) in both.
            val r = maxOf(3, photo.width / 150)
            val front = Baker.patchColour(photo, mesh.srcPx[50 * 2], mesh.srcPx[50 * 2 + 1], r)
            val sideC = Baker.patchColour(sidePhoto, sidePx[50 * 2], sidePx[50 * 2 + 1], maxOf(3, sidePhoto.width / 150))
            views.add(Baker.View(sidePhoto, sidePx, if (front != null && sideC != null) Baker.gain(sideC, front) else null))
        }
        // triView: 0 = fill, 1 = front, 2 = side  →  view index = triView - 1
        val triView = IntArray(mesh.triCount) { mesh.triView[it] - 1 }
        Baker.drawTriangles(out, mesh.uvs, mesh.tris, triView, views)
        return out
    }

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
}
