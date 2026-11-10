package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Shows the photo with the detected face mesh on top. The big dots are pins (eyes, nostrils,
 * mouth, jaw...) that can be dragged; the rest of the mesh follows smoothly.
 * One finger on a pin drags it, one finger elsewhere pans, two fingers pan and zoom.
 */
class PinEditorView(context: Context) : View(context) {

    data class Pin(val index: Int, val label: String)

    var topology: FaceTopology? = null
    var showMesh = true
        set(value) { field = value; invalidate() }
    var onChanged: (() -> Unit)? = null

    private var photo: Bitmap? = null
    private var base: FloatArray? = null
    /** Landmarks after the user's pin edits: x, y, z per point in photo pixels. */
    var current: FloatArray? = null
        private set
    private val targets = HashMap<Int, PointF>()

    private val view = Matrix()
    private val inverse = Matrix()
    private var needsFit = false

    private val dp = resources.displayMetrics.density
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 255, 200); strokeWidth = 1f }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 0) }
    private val movedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 80) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.5f * dp }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp; setShadowLayer(3 * dp, 0f, 0f, Color.BLACK) }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * dp }

    private val pts = FloatArray(FaceTopology.N * 2)
    private val mapped = FloatArray(FaceTopology.N * 2)
    private var lineBuf = FloatArray(0)

    private var activePin = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private var lastFx = 0f
    private var lastFy = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            view.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    fun setFace(photo: Bitmap, landmarks: FloatArray) {
        this.photo = photo
        base = landmarks.copyOf()
        current = landmarks.copyOf()
        targets.clear()
        fitToFace()
        invalidate()
    }

    fun resetPins() {
        targets.clear()
        current = base?.copyOf()
        invalidate()
        onChanged?.invoke()
    }

    val hasFace get() = current != null

    private fun fitToFace() {
        val lm = current ?: return
        if (width == 0 || height == 0) { needsFit = true; return }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until FaceTopology.N) {
            minX = minOf(minX, lm[i * 3]); maxX = maxOf(maxX, lm[i * 3])
            minY = minOf(minY, lm[i * 3 + 1]); maxY = maxOf(maxY, lm[i * 3 + 1])
        }
        val fw = (maxX - minX) * 1.3f
        val fh = (maxY - minY) * 1.3f
        val s = minOf(width / fw, height / fh)
        view.setScale(s, s)
        view.postTranslate(width / 2f - (minX + maxX) / 2f * s, height / 2f - (minY + maxY) / 2f * s)
        needsFit = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (needsFit || oldw == 0) fitToFace()
    }

    // ---- deformation -------------------------------------------------------------------------

    /** Gaussian RBF: every pin lands exactly on its target and nearby points follow smoothly. */
    private fun deform() {
        val b = base ?: return
        val out = b.copyOf()
        val keys = targets.keys.toIntArray()
        val n = keys.size
        if (n > 0) {
            val eye = hypot(b[FaceTopology.R_EYE_OUTER * 3] - b[FaceTopology.L_EYE_OUTER * 3], b[FaceTopology.R_EYE_OUTER * 3 + 1] - b[FaceTopology.L_EYE_OUTER * 3 + 1])
            val sigma = maxOf(4f, eye * 0.4f)
            val k = 1.0 / (2.0 * sigma * sigma)
            fun phi(i: Int, j: Int): Double {
                val dx = (b[i * 3] - b[j * 3]).toDouble(); val dy = (b[i * 3 + 1] - b[j * 3 + 1]).toDouble()
                return exp(-(dx * dx + dy * dy) * k)
            }
            val a = Array(n) { r -> DoubleArray(n) { c -> phi(keys[r], keys[c]) + if (r == c) 1e-6 else 0.0 } }
            val cx = solve(a, DoubleArray(n) { (targets[keys[it]]!!.x - b[keys[it] * 3]).toDouble() })
            val cy = solve(a, DoubleArray(n) { (targets[keys[it]]!!.y - b[keys[it] * 3 + 1]).toDouble() })
            for (i in 0 until FaceTopology.N) {
                var sx = 0.0; var sy = 0.0
                for (j in 0 until n) { val w = phi(i, keys[j]); sx += cx[j] * w; sy += cy[j] * w }
                out[i * 3] += sx.toFloat()
                out[i * 3 + 1] += sy.toFloat()
            }
        }
        current = out
    }

    private fun solve(src: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val a = Array(n) { src[it].copyOf() }
        val x = rhs.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val tx = x[col]; x[col] = x[piv]; x[piv] = tx
            val d = a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                x[r] -= f * x[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = x[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(24, 24, 28))
        val p = photo
        if (p == null) {
            canvas.drawText("Tap “Photo” to pick a front-on face photo", 16 * dp, height / 2f, textPaint)
            return
        }
        canvas.drawBitmap(p, view, photoPaint)
        val lm = current ?: return
        mapLandmarks(lm, view)

        val topo = topology
        if (showMesh && topo != null) {
            val e = topo.edges
            if (lineBuf.size != e.size * 2) lineBuf = FloatArray(e.size * 2)
            for (i in 0 until e.size / 2) {
                val a = e[i * 2]; val b = e[i * 2 + 1]
                lineBuf[i * 4] = mapped[a * 2]; lineBuf[i * 4 + 1] = mapped[a * 2 + 1]
                lineBuf[i * 4 + 2] = mapped[b * 2]; lineBuf[i * 4 + 3] = mapped[b * 2 + 1]
            }
            canvas.drawLines(lineBuf, meshPaint)
        }

        val r = 7 * dp
        for ((i, pin) in PINS.withIndex()) {
            val x = mapped[pin.index * 2]; val y = mapped[pin.index * 2 + 1]
            val rad = if (i == activePin) r * 1.4f else r
            canvas.drawCircle(x, y, rad, if (targets.containsKey(pin.index)) movedPaint else pinPaint)
            canvas.drawCircle(x, y, rad, ringPaint)
        }

        if (activePin >= 0) drawLoupe(canvas, p, PINS[activePin])
    }

    private fun mapLandmarks(lm: FloatArray, m: Matrix) {
        for (i in 0 until FaceTopology.N) { pts[i * 2] = lm[i * 3]; pts[i * 2 + 1] = lm[i * 3 + 1] }
        m.mapPoints(mapped, pts)
    }

    /** A magnified view in the corner so the pin isn't hidden under your finger. */
    private fun drawLoupe(canvas: Canvas, p: Bitmap, pin: Pin) {
        val px = mapped[pin.index * 2]; val py = mapped[pin.index * 2 + 1]
        val radius = 70 * dp
        val margin = 12 * dp
        val onLeft = px > width / 2f || py > radius * 2.5f
        val cx = if (onLeft) margin + radius else width - margin - radius
        val cy = margin + radius
        val zoom = 3f

        val m = Matrix(view)
        m.postScale(zoom, zoom, px, py)
        m.postTranslate(cx - px, cy - py)

        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(p, m, photoPaint)
        val lm = current
        val topo = topology
        if (lm != null && topo != null && showMesh) {
            val saved = mapped.copyOf()
            mapLandmarks(lm, m)
            val e = topo.edges
            val buf = FloatArray(e.size * 2)
            for (i in 0 until e.size / 2) {
                val a = e[i * 2]; val b = e[i * 2 + 1]
                buf[i * 4] = mapped[a * 2]; buf[i * 4 + 1] = mapped[a * 2 + 1]
                buf[i * 4 + 2] = mapped[b * 2]; buf[i * 4 + 3] = mapped[b * 2 + 1]
            }
            canvas.drawLines(buf, meshPaint)
            saved.copyInto(mapped)
        }
        canvas.drawLine(cx - 10 * dp, cy, cx + 10 * dp, cy, loupeBorder)
        canvas.drawLine(cx, cy - 10 * dp, cx, cy + 10 * dp, loupeBorder)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, loupeBorder)
        canvas.drawText(pin.label, cx - radius, cy + radius + 18 * dp, textPaint)
    }

    // ---- touch -------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (current == null) return true
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePin = findPin(e.x, e.y)
                if (activePin >= 0) {
                    val idx = PINS[activePin].index
                    grabDx = mapped[idx * 2] - e.x
                    grabDy = mapped[idx * 2 + 1] - e.y
                }
                focus(e, -1)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePin = -1
                focus(e, -1)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> focus(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                if (activePin >= 0 && e.pointerCount == 1) {
                    view.invert(inverse)
                    val pt = floatArrayOf(e.x + grabDx, e.y + grabDy)
                    inverse.mapPoints(pt)
                    targets[PINS[activePin].index] = PointF(pt[0], pt[1])
                    deform()
                } else {
                    val ox = lastFx; val oy = lastFy
                    focus(e, -1)
                    view.postTranslate(lastFx - ox, lastFy - oy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activePin >= 0
                activePin = -1
                invalidate()
                if (wasDragging) onChanged?.invoke()
            }
        }
        return true
    }

    private fun focus(e: MotionEvent, skip: Int) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n > 0) { lastFx = sx / n; lastFy = sy / n }
    }

    private fun findPin(x: Float, y: Float): Int {
        var best = -1
        var bestD = 32 * dp
        for ((i, pin) in PINS.withIndex()) {
            val d = hypot(mapped[pin.index * 2] - x, mapped[pin.index * 2 + 1] - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        // MediaPipe face-mesh landmark numbers. "Right"/"left" are the person's own sides.
        val PINS = listOf(
            Pin(33, "Right eye – outer"), Pin(133, "Right eye – inner"), Pin(159, "Right eye – top"), Pin(145, "Right eye – bottom"),
            Pin(263, "Left eye – outer"), Pin(362, "Left eye – inner"), Pin(386, "Left eye – top"), Pin(374, "Left eye – bottom"),
            Pin(105, "Right brow"), Pin(334, "Left brow"),
            Pin(168, "Nose bridge"), Pin(1, "Nose tip"), Pin(98, "Right nostril"), Pin(327, "Left nostril"),
            Pin(61, "Mouth – right corner"), Pin(291, "Mouth – left corner"), Pin(0, "Upper lip"), Pin(17, "Lower lip"),
            Pin(10, "Forehead"), Pin(152, "Chin"), Pin(234, "Right cheek edge"), Pin(454, "Left cheek edge"),
            Pin(172, "Right jaw"), Pin(397, "Left jaw"),
        )
    }
}
