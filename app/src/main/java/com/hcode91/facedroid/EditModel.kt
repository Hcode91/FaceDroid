package com.hcode91.facedroid

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One editable piece of a model. [role] (optional, per vertex) says which body area a vertex
 * belongs to: 0 torso, 1 head, 2 neck, 3/4 arms, 5/6 legs, 7 separate head part.
 */
class EditPart(val name: String, val base: FloatArray, val tris: IntArray, val role: ByteArray?) {
    val n = base.size / 3
    /** Current positions = sliders applied to [base], plus [delta] from the brush. */
    val cur = base.copyOf()
    var delta = FloatArray(base.size)
    /** Vertices at the same place (UV seams, unshared corners) share a group, so they always move together. */
    val group = IntArray(n)
    val used = BooleanArray(n)

    init {
        val seen = HashMap<Triple<Float, Float, Float>, Int>()
        for (v in 0 until n) group[v] = seen.getOrPut(Triple(base[v * 3], base[v * 3 + 1], base[v * 3 + 2])) { v }
        for (v in tris) used[v] = true
    }

    /** Neighbouring groups of each group (indexed by the group's first vertex). */
    val neighbours: Array<IntArray> by lazy {
        val sets = arrayOfNulls<HashSet<Int>>(n)
        for (t in 0 until tris.size / 3) for (j in 0 until 3) {
            val a = group[tris[t * 3 + j]]; val b = group[tris[t * 3 + (j + 1) % 3]]
            if (a == b) continue
            (sets[a] ?: HashSet<Int>().also { sets[a] = it }).add(b)
            (sets[b] ?: HashSet<Int>().also { sets[b] = it }).add(a)
        }
        Array(n) { sets[it]?.toIntArray() ?: IntArray(0) }
    }

    /**
     * Groups within a few steps of a join between two body areas. Slider movement is blended across
     * this band so neighbouring areas never pull apart.
     */
    val band: IntArray by lazy {
        val r = role ?: return@lazy IntArray(0)
        val nb = neighbours
        val depth = IntArray(n) { -1 }
        var frontier = ArrayList<Int>()
        for (v in 0 until n) if (group[v] == v && nb[v].any { r[it] != r[v] }) { depth[v] = 0; frontier.add(v) }
        for (ring in 1..5) {
            val next = ArrayList<Int>()
            for (v in frontier) for (o in nb[v]) if (depth[o] < 0) { depth[o] = ring; next.add(o) }
            frontier = next
        }
        (0 until n).filter { depth[it] >= 0 }.toIntArray()
    }

    fun normals(): FloatArray {
        val acc = FloatArray(n * 3)
        for (t in 0 until tris.size / 3) {
            val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
            val ux = cur[b] - cur[a]; val uy = cur[b + 1] - cur[a + 1]; val uz = cur[b + 2] - cur[a + 2]
            val vx = cur[c] - cur[a]; val vy = cur[c + 1] - cur[a + 1]; val vz = cur[c + 2] - cur[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (j in 0 until 3) { val g = group[tris[t * 3 + j]] * 3; acc[g] += nx; acc[g + 1] += ny; acc[g + 2] += nz }
        }
        val out = FloatArray(n * 3)
        for (v in 0 until n) {
            val g = group[v] * 3
            val l = sqrt(acc[g] * acc[g] + acc[g + 1] * acc[g + 1] + acc[g + 2] * acc[g + 2])
            if (l > 1e-12f) { out[v * 3] = acc[g] / l; out[v * 3 + 1] = acc[g + 1] / l; out[v * 3 + 2] = acc[g + 2] / l } else out[v * 3 + 2] = 1f
        }
        return out
    }
}

/**
 * Reshapes a model without touching its UVs: whole-model and per-body-area sliders, plus a
 * push / pull / smooth brush with undo. Body-area sliders need vertex roles (FaceDroid bodies).
 * Assumes y is up and, for bodies, +x is across the shoulders.
 */
class EditModel(val parts: List<EditPart>) {

    companion object {
        const val WIDTH = 0; const val HEIGHT = 1; const val DEPTH = 2
        const val HEAD = 3; const val SHOULDERS = 4; const val CHEST = 5; const val WAIST = 6; const val HIPS = 7
        const val ARM_THICK = 8; const val ARM_LEN = 9; const val LEG_THICK = 10; const val LEG_LEN = 11
        const val COUNT = 12
        const val PUSH = 0; const val PULL = 1; const val SMOOTH = 2
        const val ROLE_HEAD_PART: Byte = 7
    }

    class Hit(val p: FloatArray, val n: FloatArray)

    val params = FloatArray(COUNT) { 1f }
    val centre = FloatArray(3)
    val ground: Float
    val radius: Float

    private class Limb(val root: FloatArray, val axis: FloatArray, val len: Float, val top: Float, val topC: FloatArray, val botC: FloatArray, val botY: Float)
    private class Body(
        val cx: Float, val cz: Float, val tMin: Float, val tMax: Float, val neckLen: Float, val pivot: FloatArray,
        val arms: Array<Limb?>, val legs: Array<Limb?>, val legTop: Float,
    )
    private val body: Body?
    val hasBody get() = body != null

    init {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        each { p, v, _ -> for (j in 0 until 3) { min[j] = minOf(min[j], p.base[v * 3 + j]); max[j] = maxOf(max[j], p.base[v * 3 + j]) } }
        for (j in 0 until 3) centre[j] = (min[j] + max[j]) / 2
        ground = min[1]
        var r = 0f
        each { p, v, _ -> r = maxOf(r, dist(p.base[v * 3] - centre[0], p.base[v * 3 + 1] - centre[1], p.base[v * 3 + 2] - centre[2])) }
        radius = r.coerceAtLeast(1e-6f)
        body = findBody()
    }

    private fun dist(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Visit every vertex that a triangle uses. */
    private inline fun each(f: (EditPart, Int, Int) -> Unit) {
        for (p in parts) for (v in 0 until p.n) if (p.used[v]) f(p, v, p.role?.get(v)?.toInt() ?: -1)
    }

    private fun findBody(): Body? {
        if (parts.none { it.role != null }) return null
        // Torso
        var sx = 0.0; var sz = 0.0; var cnt = 0; var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE
        each { p, v, r -> if (r == 0) { sx += p.base[v * 3]; sz += p.base[v * 3 + 2]; cnt++; tMin = minOf(tMin, p.base[v * 3 + 1]); tMax = maxOf(tMax, p.base[v * 3 + 1]) } }
        if (cnt < 10) return null
        val cx = (sx / cnt).toFloat(); val cz = (sz / cnt).toFloat()

        fun verts(role: Int): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            each { p, v, r -> if (r == role) out.add(floatArrayOf(p.base[v * 3], p.base[v * 3 + 1], p.base[v * 3 + 2])) }
            return out
        }
        fun mean(l: List<FloatArray>) = FloatArray(3) { j -> l.map { it[j] }.average().toFloat() }

        val arms = Array(2) { i ->
            val vs = verts(3 + i)
            if (vs.size < 10) null else {
                val root = mean(vs.sortedBy { abs(it[0] - cx) }.take(maxOf(5, vs.size / 30)))
                val tip = vs.maxByOrNull { dist(it[0] - root[0], it[1] - root[1], it[2] - root[2]) }!!
                val len = dist(tip[0] - root[0], tip[1] - root[1], tip[2] - root[2]).coerceAtLeast(1e-3f)
                Limb(root, FloatArray(3) { (tip[it] - root[it]) / len }, len, root[1], root, tip, tip[1])
            }
        }
        val legs = Array(2) { i ->
            val vs = verts(5 + i)
            if (vs.size < 10) null else {
                val top = vs.maxOf { it[1] }; val bot = vs.minOf { it[1] }
                val len = (top - bot).coerceAtLeast(1e-3f)
                val topC = mean(vs.filter { it[1] > top - 0.2f * len })
                val low = vs.filter { it[1] > bot + 0.05f * len && it[1] < bot + 0.15f * len }
                val botC = if (low.isEmpty()) topC else mean(low)
                Limb(topC, floatArrayOf(0f, -1f, 0f), len, top, topC, botC, bot + 0.1f * len)
            }
        }
        val neck = verts(2)
        val neckLen = if (neck.isEmpty()) (tMax - tMin) * 0.15f else (neck.maxOf { it[1] } - tMax).coerceAtLeast((tMax - tMin) * 0.05f)
        val headPart = verts(ROLE_HEAD_PART.toInt())
        val head = if (headPart.isNotEmpty()) headPart else verts(1)
        val pivot = if (head.isEmpty()) floatArrayOf(cx, tMax, cz) else {
            val lo = head.minOf { it[1] }; val hi = head.maxOf { it[1] }
            floatArrayOf(mean(head)[0], lo + 0.2f * (hi - lo), mean(head)[2])
        }
        val tops = legs.filterNotNull().map { it.top }
        return Body(cx, cz, tMin, tMax, neckLen, pivot, arms, legs, if (tops.isEmpty()) tMin else tops.average().toFloat())
    }

    // ---- sliders ---------------------------------------------------------------------------------

    private fun bump(t: Float, c: Float): Float { val d = (t - c) / 0.16f; return exp(-d * d / 2) }

    private val out3 = FloatArray(3)

    /** How far the body-area sliders move a point (before the whole-model sliders). */
    private fun bodyMove(x0: Float, y0: Float, z0: Float, role: Int, out: FloatArray) {
        var dx = 0f; var dy = 0f; var dz = 0f
        val b = body
        if (b != null && role >= 0) {
            fun wideAt(t: Float) = (params[CHEST] - 1) * bump(t, 0.72f) + (params[WAIST] - 1) * bump(t, 0.45f) + (params[HIPS] - 1) * bump(t, 0.12f) + (params[SHOULDERS] - 1) * bump(t, 0.92f)
            val t = (y0 - b.tMin) / (b.tMax - b.tMin)
            val wide = wideAt(t)
            val depth = wide - (params[SHOULDERS] - 1) * bump(t, 0.92f)
            when (role) {
                1, 7 -> { val s = params[HEAD] - 1; dx += (x0 - b.pivot[0]) * s; dy += (y0 - b.pivot[1]) * s; dz += (z0 - b.pivot[2]) * s }
                0 -> { dx += (x0 - b.cx) * wide; dz += (z0 - b.cz) * depth }
                2 -> { val w = 1 - smooth((y0 - b.tMax) / b.neckLen); dx += (x0 - b.cx) * wide * w; dz += (z0 - b.cz) * depth * w }
                3, 4 -> b.arms[role - 3]?.let { a ->
                    val vx = x0 - a.root[0]; val vy = y0 - a.root[1]; val vz = z0 - a.root[2]
                    // Follow the torso near the shoulder, then move rigidly with it further down the arm.
                    val w = 1 - smooth(dist(vx, vy, vz) / (0.3f * a.len))
                    dx += w * wide * (x0 - b.cx) + (1 - w) * wideAt((a.root[1] - b.tMin) / (b.tMax - b.tMin)) * (a.root[0] - b.cx)
                    dz += w * depth * (z0 - b.cz)
                    val along = vx * a.axis[0] + vy * a.axis[1] + vz * a.axis[2]
                    val f = smooth(along / (0.15f * a.len)) * (params[ARM_THICK] - 1)
                    val l = along.coerceAtLeast(0f) * (params[ARM_LEN] - 1)
                    dx += a.axis[0] * l + (vx - along * a.axis[0]) * f
                    dy += a.axis[1] * l + (vy - along * a.axis[1]) * f
                    dz += a.axis[2] * l + (vz - along * a.axis[2]) * f
                }
                5, 6 -> b.legs[role - 5]?.let { g ->
                    val w = 1 - smooth((g.top - y0) / (0.25f * g.len))
                    // Legs stay where they are (shifting them would push the thighs into each other);
                    // only the top of the thigh follows the hips.
                    dx += w * wide * (x0 - b.cx)
                    dz += w * depth * (z0 - b.cz)
                    val k = ((g.top - y0) / (g.top - g.botY).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
                    val ax = g.topC[0] + (g.botC[0] - g.topC[0]) * k; val az = g.topC[2] + (g.botC[2] - g.topC[2]) * k
                    val f = smooth((g.top - y0) / (0.15f * g.len)) * (params[LEG_THICK] - 1)
                    // Thicken sideways from the inner thigh near the top (so the thighs don't collide),
                    // from the leg's own centre line lower down.
                    val side = if (ax >= b.cx) 1f else -1f
                    val px = ax - side * maxOf(0f, abs(ax - b.cx) - 1f) * (1 - smooth(k / 0.6f))
                    dx += (x0 - px) * f; dz += (z0 - az) * f
                }
            }
            // Longer legs lift everything above them.
            dy += (params[LEG_LEN] - 1) * (minOf(y0, b.legTop) - ground)
        }
        out[0] = dx; out[1] = dy; out[2] = dz
    }

    /** Re-apply the sliders (and brush deltas) to every vertex. */
    fun recompute() {
        for (p in parts) {
            val d = FloatArray(p.n * 3)
            val bodyOn = body != null && p.role != null && (HEAD..LEG_LEN).any { params[it] != 1f }
            if (bodyOn) {
                for (v in 0 until p.n) {
                    bodyMove(p.base[v * 3], p.base[v * 3 + 1], p.base[v * 3 + 2], p.role!![v].toInt(), out3)
                    d[v * 3] = out3[0]; d[v * 3 + 1] = out3[1]; d[v * 3 + 2] = out3[2]
                }
                // Blend the movement across the joins between body areas.
                val band = p.band; val nb = p.neighbours
                val tmp = FloatArray(band.size * 3)
                repeat(10) {
                    for ((i, v) in band.withIndex()) {
                        var x = d[v * 3]; var y = d[v * 3 + 1]; var z = d[v * 3 + 2]
                        for (o in nb[v]) { x += d[o * 3]; y += d[o * 3 + 1]; z += d[o * 3 + 2] }
                        val m = nb[v].size + 1
                        tmp[i * 3] = x / m; tmp[i * 3 + 1] = y / m; tmp[i * 3 + 2] = z / m
                    }
                    for ((i, v) in band.withIndex()) { d[v * 3] = tmp[i * 3]; d[v * 3 + 1] = tmp[i * 3 + 1]; d[v * 3 + 2] = tmp[i * 3 + 2] }
                }
                for (v in 0 until p.n) { val g = p.group[v]; if (g != v) { d[v * 3] = d[g * 3]; d[v * 3 + 1] = d[g * 3 + 1]; d[v * 3 + 2] = d[g * 3 + 2] } }
            }
            for (v in 0 until p.n) {
                p.cur[v * 3] = centre[0] + (p.base[v * 3] + d[v * 3] - centre[0]) * params[WIDTH] + p.delta[v * 3]
                p.cur[v * 3 + 1] = ground + (p.base[v * 3 + 1] + d[v * 3 + 1] - ground) * params[HEIGHT] + p.delta[v * 3 + 1]
                p.cur[v * 3 + 2] = centre[2] + (p.base[v * 3 + 2] + d[v * 3 + 2] - centre[2]) * params[DEPTH] + p.delta[v * 3 + 2]
            }
        }
    }

    // ---- brush -----------------------------------------------------------------------------------

    private val undo = ArrayList<List<FloatArray>>()
    val canUndo get() = undo.isNotEmpty()

    fun beginStroke() {
        undo.add(parts.map { it.delta.copyOf() })
        if (undo.size > 20) undo.removeAt(0)
    }

    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        val d = undo.removeAt(undo.size - 1)
        for ((i, p) in parts.withIndex()) p.delta = d[i]
        recompute()
        return true
    }

    fun resetAll() {
        params.fill(1f)
        for (p in parts) p.delta.fill(0f)
        undo.clear()
        recompute()
    }

    /** Nearest point where a ray (origin [o], unit direction [d]) hits the model. */
    fun pick(o: FloatArray, d: FloatArray): Hit? {
        var best = Float.MAX_VALUE
        var hit: Hit? = null
        for (p in parts) {
            val c = p.cur
            for (t in 0 until p.tris.size / 3) {
                val a = p.tris[t * 3] * 3; val b = p.tris[t * 3 + 1] * 3; val e = p.tris[t * 3 + 2] * 3
                val e1x = c[b] - c[a]; val e1y = c[b + 1] - c[a + 1]; val e1z = c[b + 2] - c[a + 2]
                val e2x = c[e] - c[a]; val e2y = c[e + 1] - c[a + 1]; val e2z = c[e + 2] - c[a + 2]
                val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (abs(det) < 1e-12f) continue
                val inv = 1f / det
                val tx = o[0] - c[a]; val ty = o[1] - c[a + 1]; val tz = o[2] - c[a + 2]
                val u = (tx * px + ty * py + tz * pz) * inv
                if (u < 0f || u > 1f) continue
                val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                val w = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
                if (w < 0f || u + w > 1f) continue
                val dd = (e2x * qx + e2y * qy + e2z * qz) * inv
                if (dd <= 1e-6f || dd >= best) continue
                best = dd
                var nx = e1y * e2z - e1z * e2y; var ny = e1z * e2x - e1x * e2z; var nz = e1x * e2y - e1y * e2x
                val l = dist(nx, ny, nz).coerceAtLeast(1e-12f)
                nx /= l; ny /= l; nz /= l
                if (nx * d[0] + ny * d[1] + nz * d[2] > 0) { nx = -nx; ny = -ny; nz = -nz } // face the viewer
                hit = Hit(floatArrayOf(o[0] + d[0] * dd, o[1] + d[1] * dd, o[2] + d[2] * dd), floatArrayOf(nx, ny, nz))
            }
        }
        return hit
    }

    /**
     * One dab of the brush at [hit]. PUSH/PULL move the surface by up to [amount] along the hit
     * normal; SMOOTH pulls points towards their neighbours by up to [amount] (0..1) of the way.
     */
    fun brush(hit: Hit, brushRadius: Float, amount: Float, mode: Int, mirror: Boolean = false) {
        dab(hit, brushRadius, amount, mode)
        if (mirror) dab(Hit(floatArrayOf(2 * centre[0] - hit.p[0], hit.p[1], hit.p[2]), floatArrayOf(-hit.n[0], hit.n[1], hit.n[2])), brushRadius, amount, mode)
    }

    private fun dab(hit: Hit, r: Float, amount: Float, mode: Int) {
        val r2 = r * r
        for (p in parts) {
            val c = p.cur
            if (mode == SMOOTH) {
                val nb = p.neighbours
                val moves = HashMap<Int, FloatArray>()
                for (v in 0 until p.n) {
                    if (p.group[v] != v || nb[v].isEmpty()) continue
                    val dx = c[v * 3] - hit.p[0]; val dy = c[v * 3 + 1] - hit.p[1]; val dz = c[v * 3 + 2] - hit.p[2]
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 >= r2) continue
                    var ax = 0f; var ay = 0f; var az = 0f
                    for (o in nb[v]) { ax += c[o * 3]; ay += c[o * 3 + 1]; az += c[o * 3 + 2] }
                    val k = (1 - d2 / r2).let { it * it } * amount.coerceIn(0f, 1f)
                    val m = nb[v].size
                    moves[v] = floatArrayOf((ax / m - c[v * 3]) * k, (ay / m - c[v * 3 + 1]) * k, (az / m - c[v * 3 + 2]) * k)
                }
                if (moves.isEmpty()) continue
                for (v in 0 until p.n) moves[p.group[v]]?.let { m -> for (j in 0 until 3) { p.delta[v * 3 + j] += m[j]; c[v * 3 + j] += m[j] } }
            } else {
                val s = if (mode == PULL) -amount else amount
                for (v in 0 until p.n) {
                    val dx = c[v * 3] - hit.p[0]; val dy = c[v * 3 + 1] - hit.p[1]; val dz = c[v * 3 + 2] - hit.p[2]
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 >= r2) continue
                    val k = (1 - d2 / r2).let { it * it } * s
                    for (j in 0 until 3) { p.delta[v * 3 + j] += hit.n[j] * k; c[v * 3 + j] += hit.n[j] * k }
                }
            }
        }
    }
}
