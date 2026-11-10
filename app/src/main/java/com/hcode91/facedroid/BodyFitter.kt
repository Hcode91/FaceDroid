package com.hcode91.facedroid

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/** A person/background mask: non-zero = person. */
class Mask(val width: Int, val height: Int, val data: ByteArray) {
    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height && data[y * width + x].toInt() != 0
}

/**
 * Bends the template body to match the photos:
 *  1. a thin-plate-spline warp takes template joints onto the photo pins,
 *  2. a second warp adds points along the person's outline so widths/depths match,
 *  3. front gives x/y, side gives z (or the template's depth, scaled, without a side photo),
 *  4. optional FaceDroid head replaces the template head, with the neck pulled up into it,
 *  5. each triangle is assigned the photo that sees it best (with a depth test for hidden parts).
 * Output units are centimetres with the feet at y = 0.
 */
class BodyFitter(private val t: BodyTemplate) {

    class ViewInput(val view: BodyPins.View, val pins: FloatArray, val width: Int, val height: Int, val mask: Mask?) {
        val ids = BodyPins.ids(view)
        fun pin(id: Int): FloatArray? { val i = ids.indexOf(id); return if (i < 0) null else floatArrayOf(pins[i * 2], pins[i * 2 + 1]) }
    }

    class HeadInput(val positions: FloatArray, val tris: IntArray)

    class Result(
        val positions: FloatArray,
        val normals: FloatArray,
        val tris: IntArray,
        /** Photo pixel of each vertex in front / side / back photo (side & back null if not given). */
        val viewPx: List<FloatArray?>,
        /** Per triangle: 0 front, 1 side, 2 back, -1 none (filled later). */
        val triView: IntArray,
        val headPositions: FloatArray?,
        val headNormals: FloatArray?,
    )

    private val hipY = (t.kp(23)[1] + t.kp(24)[1]) / 2
    private val hipZ = (t.kp(23)[2] + t.kp(24)[2]) / 2
    private val ankleY = (t.kp(27)[1] + t.kp(28)[1]) / 2
    private val shoulderY = (t.kp(11)[1] + t.kp(12)[1]) / 2

    fun fit(front: ViewInput, side: ViewInput?, back: ViewInput?, heightCm: Float, head: HeadInput?): Result {
        val n = t.vertexCount
        val p = t.pos
        val cmPerDm = heightCm / t.height

        // ---- front: x and y ----
        val tf = warp(front, 0, front.ids)
        val fpx = FloatArray(n * 2)
        for (v in 0 until n) tf.map(p[v * 3], p[v * 3 + 1], fpx, v * 2)
        val vTop = tf.map(t.kp(BodyTemplate.TOP)[0], t.kp(BodyTemplate.TOP)[1])[1]
        val vSole = (tf.map(t.kp(27)[0], t.minY)[1] + tf.map(t.kp(28)[0], t.minY)[1]) / 2
        val sF = heightCm / (vSole - vTop).let { if (abs(it) < 1f) 1f else it }
        val uMid = tf.map(0f, hipY)[0]

        val out = FloatArray(n * 3)
        for (v in 0 until n) {
            out[v * 3] = (fpx[v * 2] - uMid) * sF
            out[v * 3 + 1] = (vSole - fpx[v * 2 + 1]) * sF
        }

        // ---- side: z ----
        var spx: FloatArray? = null
        var sideZ: ((Float, Float) -> Float)? = null
        if (side != null) {
            // Elbow/wrist overlap the torso in a side view, so they don't drive the main warp.
            val coreIds = side.ids.filter { it != 13 && it != 15 }.toIntArray()
            val ts = warp(side, 2, coreIds)
            val s = FloatArray(n * 2)
            for (v in 0 until n) ts.map(p[v * 3 + 2], p[v * 3 + 1], s, v * 2)
            val nose = side.pin(0); val ear = side.pin(7)
            val f = if (nose != null && ear != null && nose[0] < ear[0]) -1f else 1f
            val topS = ts.map(t.kp(BodyTemplate.TOP)[2], t.kp(BodyTemplate.TOP)[1])[1]
            val soleS = ts.map(t.kp(27)[2], t.minY)[1]
            val sS = heightCm / (soleS - topS).let { if (abs(it) < 1f) 1f else it }
            val uRef = ts.map(hipZ, hipY)[0]
            val zRef = hipZ * cmPerDm
            val toZ = { u: Float, _: Float -> f * (u - uRef) * sS + zRef }
            sideZ = toZ
            for (v in 0 until n) out[v * 3 + 2] = toZ(s[v * 2], s[v * 2 + 1])

            // Arms: shear forwards/backwards so the wrist lands on the wrist pin, fading from the shoulder.
            val wrist = side.pin(15)
            if (wrist != null) {
                for ((arm, sh, wr) in listOf(Triple(BodyTemplate.ARM_L, 11, 15), Triple(BodyTemplate.ARM_R, 12, 16))) {
                    val tw = t.kp(wr); val tsh = t.kp(sh)
                    val wristNow = toZ(ts.map(tw[2], tw[1])[0], 0f)
                    val dz = toZ(wrist[0], wrist[1]) - wristNow
                    val len = dist(tsh, tw).coerceAtLeast(0.1f)
                    for (v in 0 until n) if (t.region[v].toInt() == arm) {
                        val frac = (hypot(hypot(p[v * 3] - tsh[0], p[v * 3 + 1] - tsh[1]), p[v * 3 + 2] - tsh[2]) / len).coerceIn(0f, 1f)
                        out[v * 3 + 2] += dz * smooth(frac)
                    }
                    // Keep the side-photo lookup consistent with the moved arm.
                    for (v in 0 until n) if (t.region[v].toInt() == arm) s[v * 2] = (out[v * 3 + 2] - zRef) / (f * sS) + uRef
                }
            }
            spx = s
        } else {
            for (v in 0 until n) out[v * 3 + 2] = p[v * 3 + 2] * cmPerDm
        }

        // ---- back: only used for texture ----
        val bpx = back?.let { b ->
            val tb = warp(b, 0, b.ids)
            FloatArray(n * 2).also { for (v in 0 until n) tb.map(p[v * 3], p[v * 3 + 1], it, v * 2) }
        }

        // ---- head ----
        var tris = t.tris
        var headPos: FloatArray? = null
        if (head != null) {
            val eyeZ = { id: Int ->
                val k = t.kp(id)
                if (side != null && sideZ != null) {
                    val s = warp(side, 2, side.ids.filter { it != 13 && it != 15 }.toIntArray()).map(k[2], k[1])
                    sideZ(s[0], s[1])
                } else k[2] * cmPerDm
            }
            fun eye(id: Int): FloatArray {
                val pin = front.pin(id) ?: tf.map(t.kp(id)[0], t.kp(id)[1])
                return floatArrayOf((pin[0] - uMid) * sF, (vSole - pin[1]) * sF, eyeZ(id))
            }
            val eL = eye(2); val eR = eye(5)
            val templateEyes = dist(t.kp(2), t.kp(5)) * cmPerDm
            val eyeDist = hypot(eL[0] - eR[0], eL[1] - eR[1]).coerceIn(templateEyes * 0.8f, templateEyes * 1.25f)
            val target = floatArrayOf((eL[0] + eR[0]) / 2, (eL[1] + eR[1]) / 2, (eL[2] + eR[2]) / 2)
            val placed = placeHead(head.positions, eyeDist, target)
            tris = attachNeck(out, placed, head.tris, heightCm)
            headPos = placed
        }

        val normals = FaceMesh.computeNormals(out, tris)
        val views = listOf(front, side, back)
        val pxs = listOf(fpx, spx, bpx)
        val triView = chooseViews(out, tris, views, pxs)
        return Result(out, normals, tris, pxs, triView, headPos, headPos?.let { FaceMesh.computeNormals(it, head!!.tris) })
    }

    // ---------------------------------------------------------------------------------------------

    /** Template (axis, y) → photo warp from the pins, refined with outline points if there's a mask. */
    private fun warp(v: ViewInput, axis: Int, ids: IntArray): Tps2D {
        val src = ArrayList<Float>(); val dst = ArrayList<Float>()
        for (id in ids) {
            val pin = v.pin(id) ?: continue
            val k = t.kp(id)
            src.add(k[axis]); src.add(k[1]); dst.add(pin[0]); dst.add(pin[1])
        }
        val base = Tps2D(src.toFloatArray(), dst.toFloatArray())
        val mask = v.mask ?: return base
        val extra = outline(v, axis, base, mask)
        if (extra.isEmpty()) return base
        for (i in 0 until extra.size / 4) { src.add(extra[i * 4]); src.add(extra[i * 4 + 1]); dst.add(extra[i * 4 + 2]); dst.add(extra[i * 4 + 3]) }
        return Tps2D(src.toFloatArray(), dst.toFloatArray(), smoothing = 0.02)
    }

    private val torsoVerts by lazy { verts(BodyTemplate.TORSO) }
    private val legLVerts by lazy { verts(BodyTemplate.LEG_L) }
    private val legRVerts by lazy { verts(BodyTemplate.LEG_R) }
    private fun verts(vararg regions: Int) = (0 until t.vertexCount).filter { t.region[it].toInt() in regions }.toIntArray()

    /**
     * For bands up the torso and legs, find the template's outermost points, see where the first warp
     * puts them, and move them to the actual edge of the person in the mask. Returns (a, y, u, v) quads.
     */
    private fun outline(v: ViewInput, axis: Int, tps: Tps2D, mask: Mask): FloatArray {
        val out = ArrayList<Float>()
        val legTop = hipY - 0.8f
        val groups = if (axis == 0) listOf(
            torsoVerts to (-0.4f..(shoulderY - 0.95f)),
            legLVerts to ((ankleY + 0.3f)..legTop),
            legRVerts to ((ankleY + 0.3f)..legTop),
        ) else listOf(
            torsoVerts to (-0.8f..(shoulderY - 0.6f)),
            (legLVerts + legRVerts) to ((ankleY + 0.3f)..legTop),
        )
        val step = 0.45f
        val p = t.pos

        // Arms (shoulder → elbow → wrist → hand) can overlap the outline, especially from the side.
        // Outline points near an arm are skipped; the warp fills those bands in from their neighbours.
        val pxPerDm = abs(tps.map(0f, t.maxY)[1] - tps.map(0f, t.minY)[1]) / t.height
        val armR = 0.75f * pxPerDm
        val segs = ArrayList<FloatArray>()
        for ((sh, el, wr) in listOf(Triple(11, 13, 15), Triple(12, 14, 16))) {
            val a = v.pin(sh) ?: continue; val b = v.pin(el) ?: continue; val c = v.pin(wr) ?: continue
            val hand = floatArrayOf(c[0] + (c[0] - b[0]) * 0.7f, c[1] + (c[1] - b[1]) * 0.7f)
            segs.add(a + b); segs.add(b + c); segs.add(c + hand)
        }
        fun nearArm(x: Float, y: Float) = segs.any { segDist(x, y, it) < armR }

        for ((group, range) in groups) {
            var yb = range.start
            while (yb <= range.endInclusive) {
                var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE; var minV = -1; var maxV = -1; var count = 0
                for (vi in group) {
                    if (abs(p[vi * 3 + 1] - yb) > step / 2) continue
                    val a = p[vi * 3 + axis]
                    if (a < minA) { minA = a; minV = vi }
                    if (a > maxA) { maxA = a; maxV = vi }
                    count++
                }
                yb += step
                if (count < 6) continue
                val pMin = tps.map(minA, p[minV * 3 + 1]); val pMax = tps.map(maxA, p[maxV * 3 + 1])
                val uc = (pMin[0] + pMax[0]) / 2
                val width = abs(pMax[0] - pMin[0])
                if (width < 6f) continue
                for ((a, vi, pp) in listOf(Triple(minA, minV, pMin), Triple(maxA, maxV, pMax))) {
                    if (nearArm(pp[0], pp[1])) continue
                    val edge = findEdge(mask, v.width, v.height, pp[0], pp[1], if (pp[0] >= uc) 1 else -1, width * 0.4f) ?: continue
                    if (nearArm(edge, pp[1])) continue
                    out.add(a); out.add(p[vi * 3 + 1]); out.add(edge); out.add(pp[1])
                }
            }
        }
        return out.toFloatArray()
    }

    private fun segDist(x: Float, y: Float, s: FloatArray): Float {
        val dx = s[2] - s[0]; val dy = s[3] - s[1]
        val l2 = dx * dx + dy * dy
        val k = if (l2 < 1e-6f) 0f else (((x - s[0]) * dx + (y - s[1]) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (s[0] + dx * k), y - (s[1] + dy * k))
    }

    /** Walk along the row from [u] to the person's edge (outwards if inside, inwards if outside). */
    private fun findEdge(mask: Mask, w: Int, h: Int, u: Float, vRow: Float, outward: Int, maxDist: Float): Float? {
        val sx = mask.width.toFloat() / w; val sy = mask.height.toFloat() / h
        val y = (vRow * sy).toInt()
        var x = (u * sx).toInt()
        val maxSteps = ceil(maxDist * sx).toInt().coerceAtLeast(2)
        if (y !in 0 until mask.height) return null
        if (mask.inside(x, y)) {
            for (i in 0 until maxSteps) { if (!mask.inside(x + outward, y)) return (x + 0.5f + outward * 0.5f) / sx; x += outward }
        } else {
            for (i in 0 until maxSteps) { x -= outward; if (mask.inside(x, y)) return (x + 0.5f + outward * 0.5f) / sx }
        }
        return null
    }

    // ---- head --------------------------------------------------------------------------------

    private fun placeHead(h: FloatArray, eyeDist: Float, target: FloatArray): FloatArray {
        fun mid(a: Int, b: Int, c: Int) = (h[a * 3 + c] + h[b * 3 + c]) / 2
        val r = FloatArray(3) { mid(33, 133, it) }   // right eye centre
        val l = FloatArray(3) { mid(263, 362, it) }  // left eye centre
        val hm = FloatArray(3) { (r[it] + l[it]) / 2 }
        val s = eyeDist / dist(r, l).coerceAtLeast(1e-3f)
        return FloatArray(h.size) { (h[it] - hm[it % 3]) * s + target[it % 3] }
    }

    /**
     * Drops the template head's triangles and pulls the top of the neck up inside the new head, fading
     * out down the neck, so the join is hidden. Moves [body] in place; returns the kept triangles.
     */
    private fun attachNeck(body: FloatArray, head: FloatArray, headTris: IntArray, heightCm: Float): IntArray {
        val kept = ArrayList<Int>()
        for (i in 0 until t.tris.size / 3) {
            val a = t.tris[i * 3]; val b = t.tris[i * 3 + 1]; val c = t.tris[i * 3 + 2]
            if (t.region[a].toInt() != BodyTemplate.HEAD && t.region[b].toInt() != BodyTemplate.HEAD && t.region[c].toInt() != BodyTemplate.HEAD) {
                kept.add(a); kept.add(b); kept.add(c)
            }
        }
        val tris = kept.toIntArray()

        // Open edge (welding UV-seam duplicates by template position).
        val weld = IntArray(t.vertexCount)
        val seen = HashMap<Triple<Float, Float, Float>, Int>()
        for (v in 0 until t.vertexCount) weld[v] = seen.getOrPut(Triple(t.pos[v * 3], t.pos[v * 3 + 1], t.pos[v * 3 + 2])) { v }
        val edgeCount = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = minOf(a, b).toLong() * 1_000_000 + maxOf(a, b)
        for (i in 0 until tris.size / 3) for (j in 0 until 3) {
            val k = key(weld[tris[i * 3 + j]], weld[tris[i * 3 + (j + 1) % 3]])
            edgeCount[k] = (edgeCount[k] ?: 0) + 1
        }
        val boundary = HashSet<Int>()
        for ((k, c) in edgeCount) if (c == 1) { boundary.add((k / 1_000_000).toInt()); boundary.add((k % 1_000_000).toInt()) }

        val inside = PointInMesh(head, headTris)
        var cx = 0f; var cy = 0f; var cz = 0f
        val hn = head.size / 3
        for (i in 0 until hn) { cx += head[i * 3]; cy += head[i * 3 + 1]; cz += head[i * 3 + 2] }
        val eyes = dist(FloatArray(3) { (head[33 * 3 + it] + head[133 * 3 + it]) / 2 }, FloatArray(3) { (head[263 * 3 + it] + head[362 * 3 + it]) / 2 })
        val unit = eyes / 6.3f // ≈ 1 cm at this head's scale
        val centre = floatArrayOf(cx / hn, cy / hn - 3f * unit, cz / hn - 1f * unit)
        val chinY = head[152 * 3 + 1]
        val band = 7f * heightCm / 170f

        val used = BooleanArray(t.vertexCount).also { u -> for (v in tris) u[v] = true }
        val moved = HashMap<Int, FloatArray>() // by weld group, so seam duplicates move together
        for (v in 0 until t.vertexCount) {
            if (!used[v]) continue
            val g = weld[v]
            val d = moved[g] ?: run {
                val y = body[v * 3 + 1]
                var w = if (g in boundary) 1f else ((y - (chinY - band)) / band).coerceIn(0f, 1f)
                if (w <= 0f) return@run null
                w = smooth(w)
                val s = snapInside(inside, body[v * 3], body[v * 3 + 1], body[v * 3 + 2], centre, unit)
                floatArrayOf(s[0] * w, s[1] * w, s[2] * w).also { moved[g] = it }
            } ?: continue
            body[v * 3] += d[0]; body[v * 3 + 1] += d[1]; body[v * 3 + 2] += d[2]
        }
        return tris
    }

    /** Displacement that moves point p towards [c] until it's just inside the head. */
    private fun snapInside(m: PointInMesh, x: Float, y: Float, z: Float, c: FloatArray, unit: Float): FloatArray {
        if (m.contains(x, y, z)) return FloatArray(3)
        var lo = 0f; var hi = 1f
        repeat(22) {
            val mid = (lo + hi) / 2
            if (m.contains(x + (c[0] - x) * mid, y + (c[1] - y) * mid, z + (c[2] - z) * mid)) hi = mid else lo = mid
        }
        var qx = x + (c[0] - x) * hi; var qy = y + (c[1] - y) * hi; var qz = z + (c[2] - z) * hi
        val dx = c[0] - qx; val dy = c[1] - qy; val dz = c[2] - qz
        val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        val push = minOf(0.6f * unit, l * 0.5f)
        qx += dx / l * push; qy += dy / l * push; qz += dz / l * push
        return floatArrayOf(qx - x, qy - y, qz - z)
    }

    /** Ray-parity point-in-closed-mesh test (ray towards +y). */
    private class PointInMesh(val pos: FloatArray, val tris: IntArray) {
        private val nt = tris.size / 3
        private val minX = FloatArray(nt); private val maxX = FloatArray(nt)
        private val minZ = FloatArray(nt); private val maxZ = FloatArray(nt)
        init {
            for (i in 0 until nt) {
                val a = tris[i * 3] * 3; val b = tris[i * 3 + 1] * 3; val c = tris[i * 3 + 2] * 3
                minX[i] = minOf(pos[a], pos[b], pos[c]); maxX[i] = maxOf(pos[a], pos[b], pos[c])
                minZ[i] = minOf(pos[a + 2], pos[b + 2], pos[c + 2]); maxZ[i] = maxOf(pos[a + 2], pos[b + 2], pos[c + 2])
            }
        }
        fun contains(x: Float, y: Float, z: Float): Boolean {
            var hits = 0
            for (i in 0 until nt) {
                if (x < minX[i] || x > maxX[i] || z < minZ[i] || z > maxZ[i]) continue
                val a = tris[i * 3] * 3; val b = tris[i * 3 + 1] * 3; val c = tris[i * 3 + 2] * 3
                val d = (pos[b + 2] - pos[c + 2]) * (pos[a] - pos[c]) + (pos[c] - pos[b]) * (pos[a + 2] - pos[c + 2])
                if (abs(d) < 1e-12f) continue
                val w0 = ((pos[b + 2] - pos[c + 2]) * (x - pos[c]) + (pos[c] - pos[b]) * (z - pos[c + 2])) / d
                val w1 = ((pos[c + 2] - pos[a + 2]) * (x - pos[c]) + (pos[a] - pos[c]) * (z - pos[c + 2])) / d
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val hy = w0 * pos[a + 1] + w1 * pos[b + 1] + w2 * pos[c + 1]
                if (hy > y) hits++
            }
            return hits % 2 == 1
        }
    }

    // ---- which photo textures which triangle ---------------------------------------------------

    private fun chooseViews(pos: FloatArray, tris: IntArray, views: List<ViewInput?>, pxs: List<FloatArray?>): IntArray {
        val nt = tris.size / 3
        val n = pos.size / 3
        val visible = views.mapIndexed { vi, view ->
            val px = pxs[vi]
            if (view == null || px == null) null else {
                val depth = FloatArray(n) { v ->
                    when (vi) { 0 -> pos[v * 3 + 2]; 1 -> abs(pos[v * 3]); else -> -pos[v * 3 + 2] }
                }
                visibility(px, depth, tris, view.width, view.height)
            }
        }
        val result = IntArray(nt) { -1 }
        for (i in 0 until nt) {
            val a = tris[i * 3]; val b = tris[i * 3 + 1]; val c = tris[i * 3 + 2]
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            var nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-9f) continue
            nx /= l; nz /= l
            var best = 0.08f
            for (vi in views.indices) {
                val vis = visible[vi] ?: continue
                val view = views[vi]!!
                if (!vis[a] || !vis[b] || !vis[c]) continue
                val px = pxs[vi]!!
                val mx = (px[a * 2] + px[b * 2] + px[c * 2]) / 3; val my = (px[a * 2 + 1] + px[b * 2 + 1] + px[c * 2 + 1]) / 3
                val m = view.mask
                if (m != null && !m.inside((mx * m.width / view.width).toInt(), (my * m.height / view.height).toInt())) continue
                val wgt = when (vi) { 0 -> nz; 1 -> abs(nx); else -> -nz }
                if (wgt > best) { best = wgt; result[i] = vi }
            }
        }
        return result
    }

    /** Coarse z-buffer in photo space: a vertex is visible if nothing is in front of it. */
    private fun visibility(px: FloatArray, depth: FloatArray, tris: IntArray, w: Int, h: Int): BooleanArray {
        val sc = 600f / maxOf(w, h)
        val gw = ceil(w * sc).toInt() + 1; val gh = ceil(h * sc).toInt() + 1
        val zb = FloatArray(gw * gh) { -Float.MAX_VALUE }
        for (i in 0 until tris.size / 3) {
            val a = tris[i * 3]; val b = tris[i * 3 + 1]; val c = tris[i * 3 + 2]
            val x0 = px[a * 2] * sc; val y0 = px[a * 2 + 1] * sc
            val x1 = px[b * 2] * sc; val y1 = px[b * 2 + 1] * sc
            val x2 = px[c * 2] * sc; val y2 = px[c * 2 + 1] * sc
            val d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (abs(d) < 1e-9f) continue
            val bx0 = maxOf(0, minOf(x0, x1, x2).toInt()); val bx1 = minOf(gw - 1, ceil(maxOf(x0, x1, x2)).toInt())
            val by0 = maxOf(0, minOf(y0, y1, y2).toInt()); val by1 = minOf(gh - 1, ceil(maxOf(y0, y1, y2)).toInt())
            for (y in by0..by1) for (x in bx0..bx1) {
                val fx = x + 0.5f; val fy = y + 0.5f
                val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / d
                val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / d
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * depth[a] + w1 * depth[b] + w2 * depth[c]
                val o = y * gw + x
                if (z > zb[o]) zb[o] = z
            }
        }
        val tol = 3f
        return BooleanArray(depth.size) { v ->
            val x = (px[v * 2] * sc).toInt(); val y = (px[v * 2 + 1] * sc).toInt()
            x in 0 until gw && y in 0 until gh && depth[v] >= zb[y * gw + x] - tol
        }
    }

    private fun dist(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private fun smooth(x: Float) = x * x * (3 - 2 * x)
}
