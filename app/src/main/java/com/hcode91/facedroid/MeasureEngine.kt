package com.hcode91.facedroid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

enum class MKind { LENGTH, WIDTH, DEPTH, CIRCUMFERENCE, RATIO }

enum class MStatus(val label: String) {
    MEASURED("Measured from photo"),
    ESTIMATED("Estimate (uses a shape assumption)"),
    RELATIVE("Relative only (no height entered)"),
    UNAVAILABLE("Unavailable"),
}

enum class Reliability(val label: String) { HIGH("good"), MEDIUM("fair"), LOW("rough") }

/** A line to draw on a photo. [view]: 0 = front photo, 1 = side photo. Dashed = cross-section level, not a tape path. */
class Seg(val view: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dashed: Boolean = false)

/** What a measurement's compute step returns. [value] null = unavailable ([note] says why). */
class Outcome(
    val value: Float?, val unc: Float = 0f, val estimated: Boolean = false,
    val note: String = "", val segs: List<Seg> = emptyList(), val flagged: Boolean = false,
)

/** Machine-readable definition of one measurement. Add entries to [MeasurementRegistry] to extend the app. */
class MeasurementDef(
    val id: String, val name: String, val group: String, val kind: MKind,
    val landmarks: String, val method: String, val views: String, val needsScale: Boolean,
    val limitations: String, val compute: (MeasureEngine) -> Outcome,
)

class MeasurementResult(
    val def: MeasurementDef, val status: MStatus,
    /** cm, or % of height when [status] is RELATIVE, or a plain number for ratios. */
    val value: Float?, val plusMinus: Float, val reliability: Reliability?,
    val note: String, val segs: List<Seg>,
)

/**
 * Measures a person from a front photo (and optionally a side photo) using the joint pins and
 * the person outline. Lengths come from pins, widths/depths from the outline, circumferences from
 * an ellipse through front width and side depth. Nothing is guessed: what can't be seen is
 * reported as unavailable.
 *
 * Uncertainty model (roughly one standard deviation, combined in quadrature):
 *  - a joint pin is good to about 1% of body height,
 *  - an outline edge is good to about 1.5 outline pixels (at least 0.3% of body height),
 *  - the height-based scale is good to about 1.5% (camera perspective, posture),
 *  - shape assumption: 4–5% for an ellipse from two views, 8–10% for a circle from one view.
 * Clothing is not modelled: the outline is the clothed outline.
 */
class MeasureEngine(private val front: BodyFitter.ViewInput, sideIn: BodyFitter.ViewInput?, heightCm: Float?) {

    val scaleKnown = heightCm != null
    /** Height in cm, or 100 so that every length comes out as % of height. */
    val h: Float = heightCm ?: 100f
    val warnings = ArrayList<String>()

    private val pinSigma = 0.01f * h
    private val scaleSigma = 0.015f

    // ---- one photo -------------------------------------------------------------------------------

    inner class V(val inp: BodyFitter.ViewInput, val idx: Int) {
        val mask = inp.mask
        val top: Float = inp.pin(BodyTemplate.TOP)!![1]
        val sole: Float = findSole()
        /** cm per photo pixel. */
        val cm: Float = h / (sole - top).coerceAtLeast(1f)
        private val mx: Float = (mask?.width ?: 1).toFloat() / inp.width
        private val my: Float = (mask?.height ?: 1).toFloat() / inp.height
        val edgeSigmaPx: Float = maxOf(1.5f / mx, 0.003f * (sole - top))
        val rowStep: Float = 1f / my

        fun frac(y: Float) = (sole - y) / (sole - top)
        fun yAt(frac: Float) = sole - frac * (sole - top)
        fun inside(x: Float, y: Float) = mask?.inside((x * mx).toInt(), (y * my).toInt()) ?: false

        private fun findSole(): Float {
            val ank = listOfNotNull(inp.pin(27), inp.pin(28))
            val low = listOfNotNull(inp.pin(29), inp.pin(30), inp.pin(31), inp.pin(32)).maxOfOrNull { it[1] }
            val ankleY = ank.maxOf { it[1] }
            val body = ankleY - top
            val guess = maxOf(low ?: 0f, ankleY + body * 0.02f)
            val m = mask ?: return guess
            val reach = body * (if (idx == 1) 0.16f else 0.1f)
            val x0 = ((ank.minOf { it[0] } - reach) * m.width / inp.width).toInt().coerceIn(0, m.width - 1)
            val x1 = ((ank.maxOf { it[0] } + reach) * m.width / inp.width).toInt().coerceIn(0, m.width - 1)
            val y0 = (ankleY * m.height / inp.height).toInt().coerceIn(0, m.height - 1)
            val y1 = ((ankleY + body * 0.1f) * m.height / inp.height).toInt().coerceIn(0, m.height - 1)
            var last = -1
            for (y in y0..y1) {
                var any = false
                for (x in x0..x1) if (m.inside(x, y)) { any = true; break }
                if (any) last = y else if (last >= 0) break
            }
            return if (last < 0) guess else (last + 1f) * inp.height / m.height
        }

        /** Left and right edge (photo px) of the outline on row [y], starting from [seedX]. */
        fun rowSpan(seedX: Float, y: Float): FloatArray? {
            val m = mask ?: return null
            val row = (y * my).toInt()
            if (row !in 0 until m.height) return null
            var x = (seedX * mx).toInt()
            if (!m.inside(x, row)) {
                val maxR = (m.height * 0.015f).toInt().coerceAtLeast(2)
                var found = false
                for (r in 1..maxR) {
                    if (m.inside(x + r, row)) { x += r; found = true; break }
                    if (m.inside(x - r, row)) { x -= r; found = true; break }
                }
                if (!found) return null
            }
            var l = x; while (m.inside(l - 1, row)) l--
            var r = x; while (m.inside(r + 1, row)) r++
            return floatArrayOf(l / mx, (r + 1) / mx)
        }

        /** Distance from (x, y) along unit direction (dx, dy) to the edge of the outline, or null if not inside. */
        fun march(x: Float, y: Float, dx: Float, dy: Float, maxLen: Float): Float? {
            if (mask == null || !inside(x, y)) return null
            val step = 0.5f / maxOf(mx, my)
            var d = 0f
            while (d < maxLen && inside(x + dx * (d + step), y + dy * (d + step))) d += step
            return d + step / 2
        }

        fun touchesEdge(): Boolean {
            val m = mask ?: return false
            for (x in 0 until m.width) if (m.inside(x, 0) || m.inside(x, m.height - 1)) return true
            return false
        }
    }

    private val f = V(front, 0)
    private val s: V? = sideIn?.let { V(it, 1) }

    // ---- front pins --------------------------------------------------------------------------------

    private fun fp(id: Int) = front.pin(id)!!
    private val nose = fp(0)
    private val shL = fp(11); private val shR = fp(12)
    private val elL = fp(13); private val elR = fp(14)
    private val wrL = fp(15); private val wrR = fp(16)
    private val hipL = fp(23); private val hipR = fp(24)
    private val knL = fp(25); private val knR = fp(26)
    private val anL = fp(27); private val anR = fp(28)
    private val sMid = mid(shL, shR)
    private val hMid = mid(hipL, hipR)
    private val aMid = mid(anL, anR)
    private val torsoPx = (hMid[1] - sMid[1]).coerceAtLeast(1f)

    private fun mid(a: FloatArray, b: FloatArray) = floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    private fun dist(a: FloatArray, b: FloatArray) = hypot(a[0] - b[0], a[1] - b[1])
    /** Row at fraction [fr] of the way from the shoulder line (0) to the hip-joint line (1). */
    private fun ty(fr: Float) = sMid[1] + torsoPx * fr
    private fun tx(y: Float) = sMid[0] + (hMid[0] - sMid[0]) * ((y - sMid[1]) / torsoPx)

    private fun polyX(pts: List<FloatArray>, y: Float): Float {
        val p = pts.sortedBy { it[1] }
        if (y <= p.first()[1]) return p.first()[0]
        for (i in 0 until p.size - 1) {
            val a = p[i]; val b = p[i + 1]
            if (y <= b[1]) return a[0] + (b[0] - a[0]) * ((y - a[1]) / (b[1] - a[1]).coerceAtLeast(1e-3f))
        }
        return p.last()[0]
    }

    private fun sideSeed(y: Float): Float {
        val v = s ?: return 0f
        return polyX(listOfNotNull(v.inp.pin(7), v.inp.pin(11), v.inp.pin(23), v.inp.pin(25), v.inp.pin(27)), y)
    }

    init {
        if (f.mask == null) warnings.add("No person outline was found in the front photo, so widths and circumferences are unavailable.")
        if (f.touchesEdge()) warnings.add("The person touches the top or bottom edge of the front photo – they may be cut off.")
        if (sideIn == null) warnings.add("No side photo: body depth can't be seen from the front, so torso circumferences are unavailable.")
        s?.let { sv ->
            if (sv.mask == null) warnings.add("No person outline was found in the side photo.")
            if (sv.touchesEdge()) warnings.add("The person touches the top or bottom edge of the side photo – they may be cut off.")
            val hipSide = sv.inp.pin(23)
            if (hipSide != null) {
                val d = abs(sv.frac(hipSide[1]) - f.frac(hMid[1]))
                if (d > 0.04f) warnings.add("Front and side photos disagree on hip height by ${(d * 100).toInt()}% of body height – check the pins, pose or camera height.")
            }
        }
        if (!scaleKnown) warnings.add("No height entered: lengths are shown as a percentage of body height.")
    }

    // ---- uncertainty -------------------------------------------------------------------------------

    private fun q(vararg x: Float): Float { var t = 0f; for (v in x) t += v * v; return sqrt(t) }
    private fun lenUnc(v: Float) = q(pinSigma, pinSigma, v * scaleSigma)
    private fun widthUnc(v: Float, view: V) = q(view.edgeSigmaPx * view.cm, view.edgeSigmaPx * view.cm, v * scaleSigma)

    /** Ramanujan's approximation to the perimeter of an ellipse with full axes [w] and [d]. */
    fun ellipse(w: Float, d: Float): Float {
        val a = w / 2; val b = d / 2
        return (PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))).toFloat()
    }

    private fun ellipseUnc(w: Float, d: Float, sw: Float, sd: Float, model: Float): Float {
        val c = ellipse(w, d)
        return q((ellipse(w + sw, d) - c), (ellipse(w, d + sd) - c), c * model)
    }

    private fun none(why: String) = Outcome(null, note = why)
    private val noOutline = "No person outline at this level"
    private val needSide = "Needs a side photo – depth can't be seen from the front"
    private fun n1(x: Float) = String.format(java.util.Locale.US, "%.1f", x)

    // ---- torso levels ------------------------------------------------------------------------------

    private class Level(val y: Float, val span: FloatArray?, val sideY: Float?, val depth: FloatArray?, val armTouch: Boolean) {
        val widthPx get() = span?.let { it[1] - it[0] }
        val depthPx get() = depth?.let { it[1] - it[0] }
    }

    private fun armTouches(y: Float, span: FloatArray): Boolean {
        for (arm in listOf(listOf(shL, elL, wrL), listOf(shR, elR, wrR))) {
            if (y < arm[0][1] + torsoPx * 0.12f || y > arm[2][1]) continue
            val x = polyX(arm, y)
            if (x > span[0] && x < span[1]) return true
        }
        return false
    }

    private fun level(y: Float, seedX: Float = tx(y)): Level {
        val span = f.rowSpan(seedX, y)
        val sv = s
        val sy = sv?.yAt(f.frac(y))
        val depth = if (sv != null && sy != null) sv.rowSpan(sideSeed(sy), sy) else null
        return Level(y, span, sy, depth, span != null && armTouches(y, span))
    }

    private fun search(y0: Float, y1: Float, score: (Level) -> Float?, seed: (Float) -> Float = { tx(it) }): Level? {
        var best: Level? = null; var bestScore = -Float.MAX_VALUE
        val step = maxOf((y1 - y0) / 60f, f.rowStep)
        var y = y0
        while (y <= y1) {
            val lv = level(y, seed(y))
            val sc = score(lv)
            if (sc != null && sc > bestScore) { bestScore = sc; best = lv }
            y += step
        }
        return best
    }

    private val chest by lazy { level(ty(0.25f)) }
    private val underbust by lazy { level(ty(0.45f)) }
    private val upperAbd by lazy { level(ty(0.58f)) }
    private val navel by lazy { level(ty(0.80f)) }
    private val lowerAbd by lazy { level(ty(0.92f)) }
    private val shoulderLv by lazy { level(ty(0.04f)) }
    private val waist by lazy { search(ty(0.5f), ty(0.85f), { lv -> lv.widthPx?.let { -it } }) }
    private val hip by lazy { search(ty(0.85f), ty(1.15f), { it.widthPx }) }
    private val bust by lazy { search(ty(0.2f), ty(0.45f), { lv -> if (lv.span == null) null else lv.depthPx }) }
    private val neck by lazy {
        val y0 = nose[1] + (sMid[1] - nose[1]) * 0.45f
        val y1 = sMid[1] - (sMid[1] - nose[1]) * 0.08f
        search(y0, y1, { lv -> lv.widthPx?.let { -it } }) { y -> nose[0] + (sMid[0] - nose[0]) * ((y - nose[1]) / (sMid[1] - nose[1]).coerceAtLeast(1f)) }
    }
    private val headRow by lazy { search(f.top + f.rowStep, nose[1] + (nose[1] - f.top) * 0.2f, { it.widthPx }) { nose[0] } }
    private val headSide: FloatArray? by lazy {
        val sv = s ?: return@lazy null
        val n = sv.inp.pin(0) ?: return@lazy null
        val ear = sv.inp.pin(7) ?: n
        var best: FloatArray? = null
        var y = sv.top + sv.rowStep
        val y1 = n[1] + (n[1] - sv.top) * 0.2f
        while (y <= y1) {
            val sp = sv.rowSpan((n[0] + ear[0]) / 2, y)
            if (sp != null && (best == null || sp[1] - sp[0] > best[1] - best[0])) best = floatArrayOf(sp[0], sp[1], y)
            y += maxOf((y1 - sv.top) / 60f, sv.rowStep)
        }
        best
    }

    private fun widthOf(lv: Level?): Outcome {
        val sp = lv?.span ?: return none(noOutline)
        val v = (sp[1] - sp[0]) * f.cm
        return Outcome(v, widthUnc(v, f), false,
            if (lv.armTouch) "An arm seems to touch the body at this level, so this may be too wide" else "",
            listOf(Seg(0, sp[0], lv.y, sp[1], lv.y)), lv.armTouch)
    }

    private fun depthOf(lv: Level?): Outcome {
        val sv = s ?: return none(needSide)
        val d = lv?.depth ?: return none("No person outline in the side photo at this level")
        val v = (d[1] - d[0]) * sv.cm
        return Outcome(v, widthUnc(v, sv), false, "May include the arm if it sits in front of or behind the body", listOf(Seg(1, d[0], lv.sideY!!, d[1], lv.sideY)))
    }

    private fun circOf(lv: Level?, model: Float = 0.04f): Outcome {
        val sp = lv?.span ?: return none(noOutline)
        val sv = s ?: return none(needSide)
        val d = lv.depth ?: return none("No person outline in the side photo at this level")
        val w = (sp[1] - sp[0]) * f.cm; val dep = (d[1] - d[0]) * sv.cm
        val c = ellipse(w, dep)
        val note = "Ellipse through width ${n1(w)} × depth ${n1(dep)}" + if (lv.armTouch) "; an arm seems to touch the body here" else ""
        return Outcome(c, q(ellipseUnc(w, dep, widthUnc(w, f), widthUnc(dep, sv), model)), true, note,
            listOf(Seg(0, sp[0], lv.y, sp[1], lv.y, true), Seg(1, d[0], lv.sideY!!, d[1], lv.sideY, true)), lv.armTouch)
    }

    // ---- head & neck -------------------------------------------------------------------------------

    fun headWidth() = widthOf(headRow).let { Outcome(it.value, it.unc, it.value != null, "Widest point above the nose; includes hair and ears", it.segs, it.flagged) }

    fun headCirc(): Outcome {
        val w = headRow?.span ?: return none(noOutline)
        val sv = s ?: return none(needSide)
        val d = headSide ?: return none("No head outline in the side photo")
        val wc = (w[1] - w[0]) * f.cm; val dc = (d[1] - d[0]) * sv.cm
        return Outcome(ellipse(wc, dc), ellipseUnc(wc, dc, widthUnc(wc, f), widthUnc(dc, sv), 0.05f), true,
            "Ellipse through width ${n1(wc)} × depth ${n1(dc)}; includes hair",
            listOf(Seg(0, w[0], headRow!!.y, w[1], headRow!!.y, true), Seg(1, d[0], d[2], d[1], d[2], true)))
    }

    fun neckWidth() = widthOf(neck)
    fun neckCirc(): Outcome {
        val lv = neck
        if (s != null && lv?.depth != null) return circOf(lv, 0.05f)
        val sp = lv?.span ?: return none(noOutline)
        val w = (sp[1] - sp[0]) * f.cm
        return Outcome((PI * w).toFloat(), q((PI * widthUnc(w, f)).toFloat(), (PI * w * 0.1f).toFloat()), true,
            "Assumes a round neck (no side photo), width ${n1(w)}", listOf(Seg(0, sp[0], lv.y, sp[1], lv.y, true)), true)
    }

    // ---- upper body --------------------------------------------------------------------------------

    fun shoulderJoints(): Outcome {
        val v = dist(shL, shR) * f.cm
        return Outcome(v, lenUnc(v), false, "Between the shoulder joint pins, not the outer edge", listOf(Seg(0, shL[0], shL[1], shR[0], shR[1])))
    }
    fun shoulderBreadth() = widthOf(shoulderLv).let { Outcome(it.value, it.unc, false, "Outer edge to outer edge just below the shoulder line", it.segs) }
    fun shoulderCirc() = circOf(shoulderLv, 0.08f)
    fun chestWidth() = widthOf(chest)
    fun chestDepth() = depthOf(chest)
    fun chestCirc() = circOf(chest)
    fun bustCirc() = if (s == null) none(needSide) else circOf(bust)
    fun underbustCirc() = circOf(underbust)
    fun waistWidth() = widthOf(waist)
    fun waistDepth() = depthOf(waist)
    fun waistCirc() = circOf(waist)
    fun navelCirc() = circOf(navel)
    fun upperAbdCirc() = circOf(upperAbd)
    fun lowerAbdCirc() = circOf(lowerAbd)
    fun hipWidth() = widthOf(hip)
    fun hipDepth() = depthOf(hip)
    fun hipCirc() = circOf(hip)

    private fun vertical(y0: Float?, y1: Float?, x: Float, s0: Float, s1: Float, note: String = ""): Outcome {
        if (y0 == null || y1 == null) return none(noOutline)
        val v = (y1 - y0) * f.cm
        if (v <= 0) return none("Levels are in the wrong order – check the pins")
        return Outcome(v, q(s0, s1, v * scaleSigma), false, note, listOf(Seg(0, x, y0, x, y1)))
    }
    private val edgeCm get() = f.edgeSigmaPx * f.cm

    fun shoulderToWaist() = vertical(sMid[1], waist?.y, sMid[0], pinSigma, edgeCm * 2, "Shoulder line down to the narrowest waist level")
    fun neckToWaist() = vertical(neck?.y, waist?.y, sMid[0] + torsoPx * 0.05f, edgeCm * 3, edgeCm * 2, "From the narrowest neck level, at the front")
    fun shoulderToHip(): Outcome {
        val v = dist(sMid, hMid) * f.cm
        return Outcome(v, lenUnc(v), false, "Shoulder line to hip-joint line", listOf(Seg(0, sMid[0], sMid[1], hMid[0], hMid[1])))
    }
    fun pelvisWidth(): Outcome {
        val v = dist(hipL, hipR) * f.cm
        return Outcome(v, lenUnc(v), true, "Between the hip joint pins (inside the body, estimated by the pose model)", listOf(Seg(0, hipL[0], hipL[1], hipR[0], hipR[1])))
    }

    // ---- arms --------------------------------------------------------------------------------------

    private fun pairLen(a1: FloatArray, b1: FloatArray, a2: FloatArray, b2: FloatArray): Outcome {
        val l = dist(a1, b1) * f.cm; val r = dist(a2, b2) * f.cm
        val v = (l + r) / 2
        val off = abs(l - r) > 0.15f * v
        return Outcome(v, lenUnc(v), false, "Left ${n1(l)}, right ${n1(r)}" + if (off) " – they differ a lot, check the pins or pose" else "",
            listOf(Seg(0, a1[0], a1[1], b1[0], b1[1]), Seg(0, a2[0], a2[1], b2[0], b2[1])), off)
    }
    fun upperArmLen() = pairLen(shL, elL, shR, elR)
    fun forearmLen() = pairLen(elL, wrL, elR, wrR)
    fun armLen(): Outcome {
        val a = upperArmLen(); val b = forearmLen()
        val v = a.value!! + b.value!!
        return Outcome(v, q(pinSigma, pinSigma, pinSigma, v * scaleSigma), false, "Shoulder to elbow to wrist (no hand)", a.segs + b.segs, a.flagged || b.flagged)
    }

    /** Thickness across the limb at point c, perpendicular to direction a→b. Returns the segment or null. */
    private fun across(c: FloatArray, a: FloatArray, b: FloatArray, maxLen: Float): Seg? {
        val l = dist(a, b).coerceAtLeast(1e-3f)
        val nx = -(b[1] - a[1]) / l; val ny = (b[0] - a[0]) / l
        val d1 = f.march(c[0], c[1], nx, ny, maxLen) ?: return null
        val d2 = f.march(c[0], c[1], -nx, -ny, maxLen) ?: return null
        if (d1 >= maxLen || d2 >= maxLen) return null
        return Seg(0, c[0] + nx * d1, c[1] + ny * d1, c[0] - nx * d2, c[1] - ny * d2, true)
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

    private fun roundLimb(segs: List<Seg?>, what: String): Outcome {
        val ok = segs.filterNotNull()
        if (ok.isEmpty()) return none(if (f.mask == null) noOutline else "The $what isn't clear of the body in the front photo")
        val w = ok.map { hypot(it.x2 - it.x1, it.y2 - it.y1) * f.cm }.average().toFloat()
        val c = (PI * w).toFloat()
        return Outcome(c, q((PI * widthUnc(w, f)).toFloat(), c * 0.08f), true, "Assumes a round cross-section, width ${n1(w)}", ok)
    }

    private fun armAcross(t: Float, upper: Boolean): List<Seg?> = listOf(Triple(shL, elL, wrL), Triple(shR, elR, wrR)).map { (sh, el, wr) ->
        val a = if (upper) sh else el; val b = if (upper) el else wr
        across(lerp(a, b, t), a, b, dist(a, b) * 0.35f)
    }
    fun upperArmCirc() = roundLimb(armAcross(0.5f, true), "upper arm")
    fun forearmCirc() = roundLimb(armAcross(0.3f, false), "forearm")
    fun wristCirc() = roundLimb(armAcross(0.95f, false), "wrist")
    fun elbowCirc() = roundLimb(listOf(across(elL, shL, wrL, dist(shL, elL) * 0.35f), across(elR, shR, wrR, dist(shR, elR) * 0.35f)), "elbow")

    fun handLen(): Outcome {
        val vals = ArrayList<Float>(); val segs = ArrayList<Seg>()
        for ((el, wr) in listOf(elL to wrL, elR to wrR)) {
            val l = dist(el, wr).coerceAtLeast(1e-3f)
            val dx = (wr[0] - el[0]) / l; val dy = (wr[1] - el[1]) / l
            val d = f.march(wr[0], wr[1], dx, dy, l) ?: continue
            if (d >= l || d < l * 0.2f) continue
            vals.add(d * f.cm); segs.add(Seg(0, wr[0], wr[1], wr[0] + dx * d, wr[1] + dy * d))
        }
        if (vals.isEmpty()) return none(if (f.mask == null) noOutline else "The hands aren't clear of the body in the front photo")
        val v = vals.average().toFloat()
        return Outcome(v, q(pinSigma, edgeCm, v * 0.1f), true, "Wrist pin to the end of the outline along the forearm line; needs straight, open fingers", segs, true)
    }

    fun armSpan(): Outcome {
        val hand = handLen().value ?: return none("Needs a hand length; arm span can't be seen directly unless the arms are held out level")
        val v = shoulderJoints().value!! + 2 * (armLen().value!! + hand)
        return Outcome(v, q(lenUnc(v), 2 * pinSigma, v * 0.03f), true, "Added up from shoulder width, both arms and both hands – not seen directly", emptyList(), true)
    }

    // ---- legs --------------------------------------------------------------------------------------

    private fun legSeed(i: Int, y: Float) = polyX(if (i == 0) listOf(hipL, knL, anL) else listOf(hipR, knR, anR), y)

    private class LegLv(val y: Float, val spans: List<FloatArray>, val touching: Boolean) {
        val widthPx get() = spans.map { it[1] - it[0] }.average().toFloat()
    }

    private fun legAt(y: Float): LegLv? {
        val spans = ArrayList<FloatArray>(); var touching = false
        for (i in 0..1) {
            val seed = legSeed(i, y)
            val sp = f.rowSpan(seed, y) ?: continue
            val other = legSeed(1 - i, y)
            if (other > sp[0] && other < sp[1]) {
                val c = (seed + other) / 2
                spans.add(if (seed < c) floatArrayOf(sp[0], c) else floatArrayOf(c, sp[1])); touching = true
            } else spans.add(sp)
        }
        return if (spans.isEmpty()) null else LegLv(y, spans, touching)
    }

    private fun legSearch(y0: Float, y1: Float, max: Boolean): LegLv? {
        var best: LegLv? = null
        var y = y0
        while (y <= y1) {
            val lv = legAt(y)
            if (lv != null && (best == null || (if (max) lv.widthPx > best.widthPx else lv.widthPx < best.widthPx))) best = lv
            y += maxOf((y1 - y0) / 40f, f.rowStep)
        }
        return best
    }

    private fun legCirc(lv: LegLv?): Outcome {
        lv ?: return none(noOutline)
        val w = lv.widthPx * f.cm
        val segs = ArrayList(lv.spans.map { Seg(0, it[0], lv.y, it[1], lv.y, true) })
        val touch = if (lv.touching) "; the legs touch here so they were split down the middle" else ""
        val sv = s
        if (sv != null) {
            val sy = sv.yAt(f.frac(lv.y))
            val d = sv.rowSpan(sideSeed(sy), sy)
            val dep = d?.let { (it[1] - it[0]) * sv.cm }
            if (d != null && dep != null && dep < 2.2f * w && dep > 0.45f * w) {
                segs.add(Seg(1, d[0], sy, d[1], sy, true))
                return Outcome(ellipse(w, dep), ellipseUnc(w, dep, widthUnc(w, f), widthUnc(dep, sv), 0.05f), true,
                    "Ellipse through width ${n1(w)} × depth ${n1(dep)} (side view shows both legs overlapped)$touch", segs, lv.touching)
            }
        }
        val c = (PI * w).toFloat()
        return Outcome(c, q((PI * widthUnc(w, f)).toFloat(), c * 0.1f), true, "Assumes a round cross-section, width ${n1(w)}$touch", segs, true)
    }

    /** Row where the gap between the legs starts, or null if the legs/clothing never separate. */
    private val crotch: Float? by lazy {
        if (f.mask == null) return@lazy null
        val yEnd = minOf(knL[1], knR[1])
        var y = hMid[1]
        fun cx(yy: Float) = hMid[0] + (aMid[0] - hMid[0]) * ((yy - hMid[1]) / (aMid[1] - hMid[1]).coerceAtLeast(1f))
        if (!f.inside(cx(y), y)) return@lazy null
        while (y < yEnd) { if (!f.inside(cx(y), y)) return@lazy y; y += f.rowStep }
        null
    }

    private val kneeY get() = (knL[1] + knR[1]) / 2
    private val ankleY get() = (anL[1] + anR[1]) / 2

    fun legLen() = vertical(hMid[1], f.sole, hMid[0], pinSigma, edgeCm, "Hip-joint line straight down to the floor")
    fun inseam(): Outcome {
        val c = crotch ?: return none("The legs don't separate in the outline (legs together, or a skirt/loose clothing)")
        return vertical(c, f.sole, (hMid[0] + aMid[0]) / 2, edgeCm * 2, edgeCm, "Top of the gap between the legs down to the floor; low-hanging clothing shortens it")
    }
    fun outseam(): Outcome {
        val w = waist ?: return none(noOutline)
        return vertical(w.y, f.sole, w.span!![1], edgeCm * 2, edgeCm, "Narrowest waist level straight down to the floor")
    }
    fun thighLen() = pairLen(hipL, knL, hipR, knR)
    fun lowerLegLen() = pairLen(knL, anL, knR, anR)
    fun upperThighCirc() = legCirc(legAt(crotch?.let { it + (f.sole - f.top) * 0.02f } ?: (hMid[1] + (kneeY - hMid[1]) * 0.25f)))
    fun midThighCirc() = legCirc(legAt(hMid[1] + (kneeY - hMid[1]) * 0.5f))
    fun kneeCirc() = legCirc(legAt(kneeY))
    fun calfCirc() = legCirc(legSearch(kneeY + (ankleY - kneeY) * 0.15f, kneeY + (ankleY - kneeY) * 0.45f, true))
    fun ankleCirc() = legCirc(legSearch(kneeY + (ankleY - kneeY) * 0.82f, kneeY + (ankleY - kneeY) * 0.97f, false))

    fun footLen(): Outcome {
        val sv = s ?: return none("Needs a side photo")
        val an = sv.inp.pin(27) ?: return none("No ankle pin in the side photo")
        var best: FloatArray? = null
        var y = an[1] + (sv.sole - an[1]) * 0.35f
        while (y < sv.sole - sv.rowStep * 0.5f) {
            val sp = sv.rowSpan(an[0], y)
            if (sp != null && (best == null || sp[1] - sp[0] > best[1] - best[0])) best = floatArrayOf(sp[0], sp[1], y)
            y += sv.rowStep
        }
        if (best != null) {
            val v = (best[1] - best[0]) * sv.cm
            return Outcome(v, widthUnc(v, sv), false, "Longest outline below the ankle in the side photo; includes shoes, and is too long if the feet aren't side by side",
                listOf(Seg(1, best[0], best[2], best[1], best[2])))
        }
        val heel = sv.inp.pin(29); val toe = sv.inp.pin(31)
        if (heel == null || toe == null) return none("No foot outline in the side photo")
        val v = dist(heel, toe) * sv.cm
        return Outcome(v, lenUnc(v), true, "Heel pin to toe pin in the side photo", listOf(Seg(1, heel[0], heel[1], toe[0], toe[1])), true)
    }

    fun footWidth(): Outcome {
        val vals = ArrayList<Float>(); val segs = ArrayList<Seg>()
        for ((an, toeId) in listOf(anL to 31, anR to 32)) {
            val toe = front.pin(toeId) ?: continue
            val y = an[1] + (f.sole - an[1]) * 0.75f
            val sp = f.rowSpan(toe[0], y) ?: continue
            vals.add((sp[1] - sp[0]) * f.cm); segs.add(Seg(0, sp[0], y, sp[1], y))
        }
        if (vals.isEmpty()) return none(noOutline)
        val v = vals.average().toFloat()
        return Outcome(v, q(widthUnc(v, f), v * 0.1f), true, "Outline width near the toes, feet pointing at the camera; includes shoes", segs, true)
    }

    // ---- whole body --------------------------------------------------------------------------------

    fun height() = if (scaleKnown) Outcome(h, 0f, false, "Entered by you – everything else is scaled from this", listOf(Seg(0, nose[0], f.top, nose[0], f.sole)))
        else none("Enter a height to get centimetres")

    private fun ratio(a: Outcome, b: Outcome, note: String, flagged: Boolean = false): Outcome {
        val x = a.value ?: return none(a.note); val y = b.value ?: return none(b.note)
        if (y <= 0f) return none("Can't divide by zero")
        val v = x / y
        return Outcome(v, v * q(a.unc / x, b.unc / y), a.estimated || b.estimated, note, emptyList(), flagged || a.flagged || b.flagged)
    }

    fun waistHipRatio(): Outcome {
        val a = waistCirc(); val b = hipCirc()
        if (a.value != null && b.value != null) return ratio(a, b, "Waist circumference ÷ hip circumference")
        return ratio(waistWidth(), hipWidth(), "From front widths only (no side photo), so not the usual tape-measure ratio", true)
    }
    fun shoulderHipRatio() = ratio(shoulderBreadth(), hipWidth(), "Shoulder breadth ÷ hip width, both from the front outline")
    fun torsoLegRatio() = ratio(shoulderToHip(), legLen(), "Shoulder-to-hip length ÷ leg length")
    fun armHeightRatio() = ratio(armLen(), Outcome(h), "Arm length (no hand) ÷ height")
    fun legHeightRatio() = ratio(legLen(), Outcome(h), "Leg length ÷ height")

    // ---- run ---------------------------------------------------------------------------------------

    /** Usual range as a fraction of height; values outside it are flagged, not hidden. */
    private val plausible = mapOf(
        "shoulder_width_joints" to (0.15f to 0.32f), "chest_circ" to (0.35f to 1.0f), "waist_circ" to (0.28f to 1.0f),
        "hip_circ" to (0.35f to 1.0f), "inseam" to (0.36f to 0.54f), "arm_len" to (0.25f to 0.42f),
        "leg_len" to (0.42f to 0.60f), "neck_circ" to (0.14f to 0.32f), "head_circ" to (0.27f to 0.40f),
    )

    fun run(): List<MeasurementResult> = MeasurementRegistry.all.map { def ->
        val o = try { def.compute(this) } catch (e: Exception) { none("Couldn't be worked out from these photos") }
        val v = o.value
        if (v == null || !v.isFinite() || v <= 0f) return@map MeasurementResult(def, MStatus.UNAVAILABLE, null, 0f, null, o.note.ifEmpty { "Not supported by these photos" }, emptyList())
        var note = o.note; var flagged = o.flagged
        plausible[def.id]?.let { (lo, hi) ->
            if (v < lo * h || v > hi * h) { flagged = true; note = "Outside the usual range – check the pins and outline. $note".trim() }
        }
        val status = when {
            def.kind != MKind.RATIO && !scaleKnown -> MStatus.RELATIVE
            o.estimated -> MStatus.ESTIMATED
            else -> MStatus.MEASURED
        }
        val rel = o.unc / v
        val reliability = when {
            flagged || rel > 0.08f -> Reliability.LOW
            rel > 0.04f || o.estimated -> Reliability.MEDIUM
            else -> Reliability.HIGH
        }
        MeasurementResult(def, status, v, o.unc, reliability, note, o.segs)
    }
}
