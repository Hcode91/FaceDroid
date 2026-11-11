package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Shows the front (or side) photo with every measurement drawn on it. Solid lines are distances
 * seen directly; dashed lines mark the level a circumference was estimated at. Tap a line to select it.
 */
class MeasureView(context: Context) : View(context) {
    var photos: Array<Bitmap?> = arrayOfNulls(2)
    var results: List<MeasurementResult> = emptyList()
    var selected = -1
        set(value) { field = value; invalidate() }
    var label = ""
    var showAll = true
        set(value) { field = value; invalidate() }
    var onPick: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val m = Matrix()
    private val inv = Matrix()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 230, 255); strokeWidth = 1.5f * dp }
    private val thinDash = Paint(thin).apply { pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 0); strokeWidth = 3.5f * dp; strokeCap = Paint.Cap.ROUND }
    private val boldDash = Paint(bold).apply { pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f); strokeCap = Paint.Cap.BUTT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp; setShadowLayer(4 * dp, 0f, 0f, Color.BLACK) }
    private val p4 = FloatArray(4)

    /** Side photo only when the selected measurement lives entirely on it. */
    private fun viewIndex(): Int {
        val segs = results.getOrNull(selected)?.segs ?: return 0
        return if (segs.isNotEmpty() && segs.all { it.view == 1 } && photos[1] != null) 1 else 0
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(24, 24, 28))
        val vi = viewIndex()
        val photo = photos[vi] ?: return
        val s = minOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
        m.setScale(s, s)
        m.postTranslate((width - photo.width * s) / 2f, (height - photo.height * s) / 2f)
        canvas.drawBitmap(photo, m, photoPaint)

        fun draw(seg: Seg, solid: Paint, dashed: Paint) {
            p4[0] = seg.x1; p4[1] = seg.y1; p4[2] = seg.x2; p4[3] = seg.y2
            m.mapPoints(p4)
            canvas.drawLine(p4[0], p4[1], p4[2], p4[3], if (seg.dashed) dashed else solid)
        }
        if (showAll) for ((i, r) in results.withIndex()) if (i != selected) for (seg in r.segs) if (seg.view == vi) draw(seg, thin, thinDash)
        val sel = results.getOrNull(selected) ?: return
        var lx = 12 * dp; var ly = 24 * dp
        for (seg in sel.segs) if (seg.view == vi) {
            draw(seg, bold, boldDash)
            if (!seg.dashed) { canvas.drawCircle(p4[0], p4[1], 4 * dp, bold); canvas.drawCircle(p4[2], p4[3], 4 * dp, bold) }
            lx = minOf(p4[0], p4[2]); ly = minOf(p4[1], p4[3]) - 8 * dp
        }
        val tw = text.measureText(label)
        canvas.drawText(label, lx.coerceIn(4 * dp, maxOf(4 * dp, width - tw - 4 * dp)), ly.coerceAtLeast(18 * dp), text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val vi = viewIndex()
        if (photos[vi] == null || !m.invert(inv)) return true
        val p = floatArrayOf(e.x, e.y)
        inv.mapPoints(p)
        val reach = inv.mapRadius(28 * dp)
        var best = -1; var bestD = reach
        for ((i, r) in results.withIndex()) for (seg in r.segs) {
            if (seg.view != vi) continue
            val dx = seg.x2 - seg.x1; val dy = seg.y2 - seg.y1
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6f) 0f else (((p[0] - seg.x1) * dx + (p[1] - seg.y1) * dy) / l2).coerceIn(0f, 1f)
            // Prefer short lines when several overlap, so small measurements stay reachable.
            val d = hypot(p[0] - (seg.x1 + dx * t), p[1] - (seg.y1 + dy * t)) + kotlin.math.sqrt(l2) * 0.02f
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) onPick?.invoke(best)
        return true
    }
}
