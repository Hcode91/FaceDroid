package com.hcode91.facedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Validation against a synthetic mannequin with known dimensions: a 180 cm figure built from
 * ellipses, boxes and capsules, drawn as front and side outlines at 8 px per cm.
 */
class MeasureTest {
    private val w = 1000; private val h = 1600
    private val k = 8f; private val cx = 500f; private val ground = 1550f

    private fun lin(pts: List<Pair<Float, Float>>, y: Float): Float {
        for (i in 0 until pts.size - 1) {
            val (h0, v0) = pts[i]; val (h1, v1) = pts[i + 1]
            if (y <= h0 && y >= h1) return v0 + (v1 - v0) * ((h0 - y) / (h0 - h1))
        }
        return pts.last().second
    }
    private val torsoW = listOf(148f to 40f, 132f to 36f, 111.6f to 30f, 92f to 38f, 82f to 38f)
    private val torsoD = listOf(148f to 16f, 132f to 24f, 111.6f to 20f, 92f to 24f, 82f to 22f)
    private val legW = listOf(82f to 17f, 48f to 12f, 35f to 13f, 8f to 7f, 0f to 7f)
    private val legD = listOf(82f to 20f, 48f to 13f, 35f to 13f, 8f to 8f, 0f to 8f)

    private fun capsule(x: Float, y: Float, a: FloatArray, b: FloatArray, r: Float): Boolean {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val t = (((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(x - (a[0] + dx * t), y - (a[1] + dy * t)) <= r
    }

    private fun frontInside(x: Float, y: Float): Boolean {
        if ((x / 8).let { it * it } + ((y - 168) / 12).let { it * it } <= 1f) return true
        if (y in 148f..158f && kotlin.math.abs(x) <= 6f) return true
        if (y in 82f..148f && kotlin.math.abs(x) <= lin(torsoW, y) / 2) return true
        for (c in listOf(-10f, 10f)) {
            if (y in 0f..82f && kotlin.math.abs(x - c) <= lin(legW, y) / 2) return true
            if (y in 0f..6f && kotlin.math.abs(x - c) <= 4.5f) return true
            val sg = if (c > 0) 1f else -1f
            val sh = floatArrayOf(19f * sg, 148f); val el = floatArrayOf(33f * sg, 121f); val wr = floatArrayOf(45f * sg, 97f)
            val l = hypot(wr[0] - el[0], wr[1] - el[1])
            val hand = floatArrayOf(wr[0] + (wr[0] - el[0]) / l * 18f, wr[1] + (wr[1] - el[1]) / l * 18f)
            if (capsule(x, y, sh, el, 4.5f) || capsule(x, y, el, wr, 3.5f) || capsule(x, y, wr, hand, 3.5f)) return true
        }
        return false
    }

    private fun sideInside(z: Float, y: Float): Boolean {
        if ((z / 10).let { it * it } + ((y - 168) / 12).let { it * it } <= 1f) return true
        if (y in 148f..158f && kotlin.math.abs(z) <= 6f) return true
        if (y in 82f..148f && kotlin.math.abs(z) <= lin(torsoD, y) / 2) return true
        if (y in 0f..82f && kotlin.math.abs(z) <= lin(legD, y) / 2) return true
        return y in 0f..6f && z in -8f..18f
    }

    private fun mask(inside: (Float, Float) -> Boolean) = Mask(w, h, ByteArray(w * h) { i ->
        val px = i % w; val py = i / w
        if (inside((px + 0.5f - cx) / k, (ground - (py + 0.5f)) / k)) 1 else 0
    })

    private fun pins(view: BodyPins.View, pts: Map<Int, Pair<Float, Float>>): FloatArray {
        val ids = BodyPins.ids(view)
        return FloatArray(ids.size * 2) { i -> val p = pts[ids[i / 2]]!!; if (i % 2 == 0) cx + p.first * k else ground - p.second * k }
    }

    private val frontPts = mapOf(
        BodyTemplate.TOP to (0f to 180f), 0 to (0f to 166f), 2 to (3f to 170f), 5 to (-3f to 170f),
        11 to (19f to 148f), 12 to (-19f to 148f), 13 to (33f to 121f), 14 to (-33f to 121f), 15 to (45f to 97f), 16 to (-45f to 97f),
        23 to (10f to 92f), 24 to (-10f to 92f), 25 to (10f to 48f), 26 to (-10f to 48f), 27 to (10f to 8f), 28 to (-10f to 8f),
        31 to (10f to 1f), 32 to (-10f to 1f),
    )
    private val sidePts = mapOf(
        BodyTemplate.TOP to (0f to 180f), 0 to (10f to 166f), 7 to (0f to 166f), 11 to (0f to 148f), 13 to (0f to 121f),
        15 to (0f to 97f), 23 to (0f to 92f), 25 to (0f to 48f), 27 to (0f to 8f), 29 to (-8f to 1f), 31 to (18f to 1f),
    )

    private fun front(withMask: Boolean = true) = BodyFitter.ViewInput(BodyPins.View.FRONT, pins(BodyPins.View.FRONT, frontPts), w, h, if (withMask) mask(::frontInside) else null)
    private fun side() = BodyFitter.ViewInput(BodyPins.View.SIDE, pins(BodyPins.View.SIDE, sidePts), w, h, mask(::sideInside))

    private fun ellipse(a: Float, b: Float): Float {
        val x = a / 2; val y = b / 2
        return (PI * (3 * (x + y) - sqrt((3 * x + y) * (x + 3 * y)))).toFloat()
    }

    @Test
    fun mannequinFrontAndSide() {
        val eng = MeasureEngine(front(), side(), 180f)
        val res = eng.run().associateBy { it.def.id }
        val errors = ArrayList<String>()
        fun check(id: String, want: Float, tol: Float) {
            val r = res[id]!!
            val got = r.value
            println(String.format("%-22s want %6.1f got %s ± %.1f  [%s, %s] %s", id, want, got?.let { String.format("%6.1f", it) } ?: "  none", r.plusMinus, r.status, r.reliability, r.note))
            if (got == null || kotlin.math.abs(got - want) > tol) errors.add("$id: want $want got $got")
        }
        check("height", 180f, 0.01f)
        check("shoulder_width_joints", 38f, 0.3f)
        check("chest_width", 36.5f, 0.5f)
        check("waist_width", 30f, 0.5f)
        check("waist_depth", 20f, 0.5f)
        check("waist_circ", ellipse(30f, 20f), 1.5f)
        check("hip_width", 38f, 0.5f)
        check("hip_circ", ellipse(38f, 24f), 2.5f)
        check("neck_width", 12f, 0.4f)
        check("neck_circ", ellipse(12f, 12f), 1.2f)
        check("head_width", 16f, 0.4f)
        check("head_circ", ellipse(16f, 20f), 1.5f)
        check("upper_arm_len", 30.41f, 0.2f)
        check("forearm_len", 26.83f, 0.2f)
        check("upper_arm_circ", (PI * 9).toFloat(), 1.2f)
        check("forearm_circ", (PI * 7).toFloat(), 1.2f)
        check("leg_len", 92f, 0.4f)
        check("inseam", 82f, 0.6f)
        check("thigh_len", 44f, 0.2f)
        check("lower_leg_len", 40f, 0.2f)
        check("mid_thigh_circ", ellipse(15.24f, 17.53f), 1.5f)
        check("calf_circ", ellipse(13f, 13f), 1.5f)
        check("foot_len", 26f, 0.5f)
        check("shoulder_to_hip", 56f, 0.3f)
        check("waist_hip_ratio", ellipse(30f, 20f) / ellipse(38f, 24f), 0.03f)
        assertTrue(errors.joinToString("\n"), errors.isEmpty())

        // Every value sits inside a non-zero ± and nothing claims more than it has.
        for (r in res.values) if (r.value != null && r.def.id != "height") assertTrue(r.def.id, r.plusMinus > 0f)
        assertEquals(MStatus.ESTIMATED, res["waist_circ"]!!.status)
        assertEquals(MStatus.MEASURED, res["waist_width"]!!.status)
        assertTrue(res["chest_circ"]!!.reliability != Reliability.LOW)
        assertNull(res["sitting_height"]!!.value)
        assertEquals(MStatus.UNAVAILABLE, res["head_height"]!!.status)
    }

    @Test
    fun singlePhotoHasNoTorsoCircumferences() {
        val eng = MeasureEngine(front(), null, 180f)
        val res = eng.run().associateBy { it.def.id }
        for (id in listOf("chest_circ", "waist_circ", "hip_circ", "bust_circ", "head_circ", "foot_len")) assertNull(id, res[id]!!.value)
        assertNotNull(res["waist_width"]!!.value)
        assertNotNull(res["inseam"]!!.value)
        // Limbs fall back to a circle and say so.
        assertEquals(Reliability.LOW, res["calf_circ"]!!.reliability)
        assertTrue(eng.warnings.any { it.contains("side photo") })
    }

    @Test
    fun noOutlineGivesLengthsOnly() {
        val res = MeasureEngine(front(withMask = false), null, 180f).run().associateBy { it.def.id }
        assertNull(res["waist_width"]!!.value)
        assertNull(res["inseam"]!!.value)
        assertNull(res["upper_arm_circ"]!!.value)
        assertEquals(30.41f, res["upper_arm_len"]!!.value!!, 1.0f)
    }

    @Test
    fun noHeightGivesRelativeValues() {
        val res = MeasureEngine(front(), side(), null).run().associateBy { it.def.id }
        val sh = res["shoulder_width_joints"]!!
        assertEquals(MStatus.RELATIVE, sh.status)
        assertEquals(38f / 180f * 100f, sh.value!!, 0.3f)
        assertNull(res["height"]!!.value)
        assertEquals(MStatus.ESTIMATED, res["waist_hip_ratio"]!!.status)
    }

    @Test
    fun armAgainstBodyIsFlagged() {
        // Move the left elbow and wrist pins onto the torso: the chest row now contains the arm line.
        val pts = HashMap(frontPts); pts[13] = 14f to 121f; pts[15] = 12f to 97f
        val f = BodyFitter.ViewInput(BodyPins.View.FRONT, pins(BodyPins.View.FRONT, pts), w, h, mask(::frontInside))
        val res = MeasureEngine(f, side(), 180f).run().associateBy { it.def.id }
        assertEquals(Reliability.LOW, res["waist_width"]!!.reliability)
    }

    @Test
    fun exportsAreWellFormed() {
        val eng = MeasureEngine(front(), side(), 180f)
        val res = eng.run()
        assertEquals(MeasurementRegistry.all.size, MeasurementRegistry.all.map { it.id }.toSet().size)
        val csv = MeasureFormat.csv(res)
        assertEquals(res.size + 1, csv.trim().lines().size)
        val json = MeasureFormat.json(res, eng.warnings + "quote \" and \\ slash", 180f, "2026-11-11")
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertTrue(json.contains("\"id\": \"waist_circ\"") && json.contains("\\\" and \\\\ slash"))
        assertTrue(MeasureFormat.value(res.first { it.def.id == "height" }, true).endsWith("in"))
    }
}
