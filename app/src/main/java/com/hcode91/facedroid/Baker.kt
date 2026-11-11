package com.hcode91.facedroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sqrt

/** Copies triangles from one or more photos into a UV texture. */
object Baker {

    /** A photo plus where every mesh vertex sits in it. [gain] multiplies R, G, B to match colours between photos. */
    class View(val photo: Bitmap, val px: FloatArray, val gain: FloatArray? = null)

    /** Draw every triangle whose [triView] entry is a valid index into [views]. */
    fun drawTriangles(target: Bitmap, uvs: FloatArray, tris: IntArray, triView: IntArray, views: List<View?>) {
        val canvas = Canvas(target)
        val w = target.width; val h = target.height
        val paints = views.map { v ->
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                v?.gain?.let { g -> colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(g[0], g[1], g[2], 1f) }) }
            }
        }
        val src = FloatArray(6); val dst = FloatArray(6)
        val m = Matrix(); val path = Path()
        val grow = 1.5f
        for (t in 0 until tris.size / 3) {
            val vi = triView[t]
            if (vi < 0 || vi >= views.size) continue
            val view = views[vi] ?: continue
            for (j in 0 until 3) {
                val v = tris[t * 3 + j]
                src[j * 2] = view.px[v * 2]; src[j * 2 + 1] = view.px[v * 2 + 1]
                dst[j * 2] = uvs[v * 2] * w; dst[j * 2 + 1] = (1f - uvs[v * 2 + 1]) * h
            }
            if (!m.setPolyToPoly(src, 0, dst, 0, 3)) continue
            val cx = (dst[0] + dst[2] + dst[4]) / 3f
            val cy = (dst[1] + dst[3] + dst[5]) / 3f
            path.rewind()
            for (j in 0 until 3) {
                val dx = dst[j * 2] - cx; val dy = dst[j * 2 + 1] - cy
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val x = dst[j * 2] + dx / len * grow; val y = dst[j * 2 + 1] + dy / len * grow
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(view.photo, m, paints[vi])
            canvas.restore()
        }
    }

    /**
     * A plain skin texture with no clothing: [colour] everywhere, very slightly darker on surfaces
     * that face downwards so the body doesn't look completely flat. No photo pixels are used.
     */
    fun skinBase(size: Int, uvs: FloatArray, tris: IntArray, normals: FloatArray, colour: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(colour)
        val paint = Paint()
        val chunk = 3000 // triangles per draw call
        val nt = tris.size / 3
        var t0 = 0
        while (t0 < nt) {
            val n = minOf(chunk, nt - t0)
            val verts = FloatArray(n * 6); val cols = IntArray(n * 3)
            for (i in 0 until n * 3) {
                val v = tris[t0 * 3 + i]
                verts[i * 2] = uvs[v * 2] * size; verts[i * 2 + 1] = (1f - uvs[v * 2 + 1]) * size
                val s = 0.94f + 0.06f * normals[v * 3 + 1].coerceIn(-1f, 1f)
                cols[i] = Color.rgb((Color.red(colour) * s).toInt().coerceIn(0, 255), (Color.green(colour) * s).toInt().coerceIn(0, 255), (Color.blue(colour) * s).toInt().coerceIn(0, 255))
            }
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, null, 0, cols, 0, null, 0, 0, paint)
            t0 += n
        }
        return out
    }

    /** Average photo colour over the person mask (every few pixels). */
    fun maskColour(photo: Bitmap, mask: Mask): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = maxOf(1, mask.width / 200)
        for (y in 0 until mask.height step step) for (x in 0 until mask.width step step) {
            if (!mask.inside(x, y)) continue
            val px = (x * photo.width / mask.width).coerceIn(0, photo.width - 1)
            val py = (y * photo.height / mask.height).coerceIn(0, photo.height - 1)
            val c = photo.getPixel(px, py)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Average colour of a square patch, or null if it's off the photo. */
    fun patchColour(photo: Bitmap, px: Float, py: Float, radius: Int): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in py.toInt() - radius..py.toInt() + radius) for (x in px.toInt() - radius..px.toInt() + radius) {
            if (x !in 0 until photo.width || y !in 0 until photo.height) continue
            val c = photo.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Per-channel gain that turns colour [from] into colour [to], kept within sensible limits. */
    fun gain(from: Int, to: Int): FloatArray {
        fun g(a: Int, b: Int) = ((b + 1f) / (a + 1f)).coerceIn(0.6f, 1.6f)
        return floatArrayOf(g(Color.red(from), Color.red(to)), g(Color.green(from), Color.green(to)), g(Color.blue(from), Color.blue(to)))
    }

    /**
     * Fills the transparent parts of [baked] with colours smoothly spread from the filled parts
     * ("pull-push"), so gaps blend into what's around them. Returns a new opaque bitmap.
     */
    fun fillGaps(baked: Bitmap, workSize: Int = 1024): Bitmap {
        val small = Bitmap.createScaledBitmap(baked, workSize, workSize, true)
        val px = IntArray(workSize * workSize)
        small.getPixels(px, 0, workSize, 0, 0, workSize, workSize)

        // Level 0, premultiplied colour + coverage.
        val levels = ArrayList<FloatArray>() // r, g, b, a interleaved
        val sizes = ArrayList<Int>()
        var size = workSize
        var cur = FloatArray(size * size * 4)
        for (i in px.indices) {
            val c = px[i]; val a = Color.alpha(c) / 255f
            cur[i * 4] = Color.red(c) / 255f * a; cur[i * 4 + 1] = Color.green(c) / 255f * a
            cur[i * 4 + 2] = Color.blue(c) / 255f * a; cur[i * 4 + 3] = a
        }
        levels.add(cur); sizes.add(size)
        // Pull: halve until 1x1; a coarse pixel is fully covered if its children have any coverage.
        while (size > 1) {
            val ns = size / 2
            val next = FloatArray(ns * ns * 4)
            for (y in 0 until ns) for (x in 0 until ns) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val o = ((y * 2 + dy) * size + x * 2 + dx) * 4
                    r += cur[o]; g += cur[o + 1]; b += cur[o + 2]; a += cur[o + 3]
                }
                val o = (y * ns + x) * 4
                if (a > 1e-6f) {
                    val na = minOf(1f, a)
                    val k = na / a
                    next[o] = r * k; next[o + 1] = g * k; next[o + 2] = b * k; next[o + 3] = na
                }
            }
            levels.add(next); sizes.add(ns)
            cur = next; size = ns
        }
        // Push: fill each level's gaps from the (already filled) coarser level.
        for (l in levels.size - 2 downTo 0) {
            val fine = levels[l]; val fs = sizes[l]
            val coarse = levels[l + 1]; val cs = sizes[l + 1]
            for (y in 0 until fs) for (x in 0 until fs) {
                val o = (y * fs + x) * 4
                val a = fine[o + 3]
                if (a >= 0.999f) continue
                val co = ((minOf(y / 2, cs - 1)) * cs + minOf(x / 2, cs - 1)) * 4
                val ca = coarse[co + 3].coerceAtLeast(1e-6f)
                val inv = 1f - a
                fine[o] += inv * coarse[co] / ca; fine[o + 1] += inv * coarse[co + 1] / ca
                fine[o + 2] += inv * coarse[co + 2] / ca; fine[o + 3] = 1f
            }
        }
        val top = levels[0]
        for (i in px.indices) {
            val a = top[i * 4 + 3].coerceAtLeast(1e-6f)
            px[i] = Color.rgb(
                (top[i * 4] / a * 255).toInt().coerceIn(0, 255),
                (top[i * 4 + 1] / a * 255).toInt().coerceIn(0, 255),
                (top[i * 4 + 2] / a * 255).toInt().coerceIn(0, 255)
            )
        }
        small.setPixels(px, 0, workSize, 0, 0, workSize, workSize)
        val out = Bitmap.createScaledBitmap(small, baked.width, baked.height, true).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(baked, 0f, 0f, null)
        return out
    }
}
