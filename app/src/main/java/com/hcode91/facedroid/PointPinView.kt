package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * A photo with a set of named, draggable pins (side profile, body joints...).
 * One finger on a pin drags it, one finger elsewhere pans, two fingers pan and zoom.
 */
class PointPinView(context: Context) : View(context) {

    class Pin(val label: String, val colour: Int = Color.rgb(255, 200, 0))

    var onChanged: (() -> Unit)? = null
    var hint = "Pick a photo"
        set(value) { field = value; invalidate() }
    var showOverlay = true
        set(value) { field = value; invalidate() }

    private var photo: Bitmap? = null
    private var overlay: Bitmap? = null
    private var pins: List<Pin> = emptyList()
    private var pts = FloatArray(0)
    private var lines = IntArray(0)

    /** Pin positions in photo pixels, x/y interleaved. */
    val points: FloatArray get() = pts.copyOf()
    val hasPhoto get() = photo != null

    private val view = Matrix()
    private val inverse = Matrix()
    private var needsFit = false

    private val dp = resources.displayMetrics.density
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 255, 200); strokeWidth = 2 * dp }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * dp }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp; setShadowLayer(3 * dp, 0f, 0f, Color.BLACK) }
    private val smallText = Paint(textPaint).apply { textSize = 11 * dp }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * dp }

    private var mapped = FloatArray(0)
    private var active = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    var showLabels = true

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            view.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    fun set(photo: Bitmap, pins: List<Pin>, points: FloatArray, lines: IntArray = IntArray(0), overlay: Bitmap? = null) {
        this.photo = photo
        this.pins = pins
        this.pts = points.copyOf()
        this.lines = lines
        this.overlay = overlay
        mapped = FloatArray(points.size)
        fit()
        invalidate()
    }

    fun setPoints(points: FloatArray) {
        pts = points.copyOf()
        invalidate()
        onChanged?.invoke()
    }

    private fun fit() {
        val p = photo ?: return
        if (width == 0 || height == 0) { needsFit = true; return }
        val s = minOf(width.toFloat() / p.width, height.toFloat() / p.height)
        view.setScale(s, s)
        view.postTranslate((width - p.width * s) / 2f, (height - p.height * s) / 2f)
        needsFit = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (needsFit || oldw == 0) fit()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(24, 24, 28))
        val p = photo
        if (p == null) {
            canvas.drawText(hint, 16 * dp, height / 2f, textPaint)
            return
        }
        canvas.drawBitmap(p, view, photoPaint)
        overlay?.let { if (showOverlay) canvas.drawBitmap(it, Matrix(view).apply { preScale(p.width.toFloat() / it.width, p.height.toFloat() / it.height) }, overlayPaint) }
        view.mapPoints(mapped, pts)
        for (i in 0 until lines.size / 2) {
            val a = lines[i * 2]; val b = lines[i * 2 + 1]
            canvas.drawLine(mapped[a * 2], mapped[a * 2 + 1], mapped[b * 2], mapped[b * 2 + 1], linePaint)
        }
        val r = 7 * dp
        for (i in pins.indices) {
            val x = mapped[i * 2]; val y = mapped[i * 2 + 1]
            fillPaint.color = pins[i].colour
            val rad = if (i == active) r * 1.4f else r
            canvas.drawCircle(x, y, rad, fillPaint)
            canvas.drawCircle(x, y, rad, ringPaint)
            if (showLabels && active < 0) canvas.drawText(pins[i].label, x + rad + 3 * dp, y + 4 * dp, smallText)
        }
        if (active >= 0) drawLoupe(canvas, p, active)
    }

    private fun drawLoupe(canvas: Canvas, p: Bitmap, i: Int) {
        val px = mapped[i * 2]; val py = mapped[i * 2 + 1]
        val radius = 70 * dp
        val margin = 12 * dp
        val onLeft = px > width / 2f || py > radius * 2.5f
        val cx = if (onLeft) margin + radius else width - margin - radius
        val cy = margin + radius
        val m = Matrix(view)
        m.postScale(3f, 3f, px, py)
        m.postTranslate(cx - px, cy - py)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(p, m, photoPaint)
        canvas.drawLine(cx - 10 * dp, cy, cx + 10 * dp, cy, loupeBorder)
        canvas.drawLine(cx, cy - 10 * dp, cx, cy + 10 * dp, loupeBorder)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, loupeBorder)
        canvas.drawText(pins[i].label, cx - radius, cy + radius + 18 * dp, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (photo == null) return true
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.mapPoints(mapped, pts)
                active = findPin(e.x, e.y)
                if (active >= 0) { grabDx = mapped[active * 2] - e.x; grabDy = mapped[active * 2 + 1] - e.y }
                focus(e, -1)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { active = -1; focus(e, -1); invalidate() }
            MotionEvent.ACTION_POINTER_UP -> focus(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0 && e.pointerCount == 1) {
                    view.invert(inverse)
                    val q = floatArrayOf(e.x + grabDx, e.y + grabDy)
                    inverse.mapPoints(q)
                    pts[active * 2] = q[0]; pts[active * 2 + 1] = q[1]
                } else {
                    val ox = lastFx; val oy = lastFy
                    focus(e, -1)
                    view.postTranslate(lastFx - ox, lastFy - oy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = active >= 0
                active = -1
                invalidate()
                if (was) onChanged?.invoke()
            }
        }
        return true
    }

    private fun focus(e: MotionEvent, skip: Int) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) { if (i == skip) continue; sx += e.getX(i); sy += e.getY(i); n++ }
        if (n > 0) { lastFx = sx / n; lastFy = sy / n }
    }

    private fun findPin(x: Float, y: Float): Int {
        var best = -1
        var bestD = 32 * dp
        for (i in pins.indices) {
            val d = hypot(mapped[i * 2] - x, mapped[i * 2 + 1] - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
