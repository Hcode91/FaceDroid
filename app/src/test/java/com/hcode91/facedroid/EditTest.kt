package com.hcode91.facedroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class EditTest {
    private val t = listOf("src/main/assets/body_template.txt", "app/src/main/assets/body_template.txt").map(::File).first { it.exists() }
        .inputStream().use { BodyTemplate.parse(it) }

    /** The template body in cm with feet on the ground, like a fitted FaceDroid body. */
    private fun model(): EditModel {
        val pos = FloatArray(t.pos.size) { i -> if (i % 3 == 1) (t.pos[i] - t.minY) * 10f else t.pos[i] * 10f }
        return EditModel(listOf(EditPart("body", pos, t.tris, t.region)))
    }

    private fun width(m: EditModel, role: Int, y0: Float, y1: Float, axis: Int = 0): Float {
        val p = m.parts[0]
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in 0 until p.n) {
            if (!p.used[v] || p.role!![v].toInt() != role || p.cur[v * 3 + 1] < y0 || p.cur[v * 3 + 1] > y1) continue
            lo = minOf(lo, p.cur[v * 3 + axis]); hi = maxOf(hi, p.cur[v * 3 + axis])
        }
        return hi - lo
    }
    private fun top(m: EditModel) = m.parts[0].let { p -> (0 until p.n).filter { p.used[it] }.maxOf { v -> p.cur[v * 3 + 1] } }

    /** No edge may stretch or shrink wildly – that would mean the mesh tore between body areas. */
    private fun worstEdge(m: EditModel): Float {
        val p = m.parts[0]
        var worst = 1f
        fun len(a: FloatArray, i: Int, j: Int) = sqrt((0 until 3).sumOf { ((a[i * 3 + it] - a[j * 3 + it]) * (a[i * 3 + it] - a[j * 3 + it])).toDouble() }).toFloat()
        for (k in 0 until p.tris.size / 3) for (j in 0 until 3) {
            val a = p.tris[k * 3 + j]; val b = p.tris[k * 3 + (j + 1) % 3]
            val l0 = len(p.base, a, b); if (l0 < 0.05f) continue
            val r = len(p.cur, a, b) / l0
            worst = maxOf(worst, r, 1 / r.coerceAtLeast(1e-6f))
        }
        return worst
    }

    @Test
    fun slidersAtDefaultChangeNothing() {
        val m = model()
        assertTrue(m.hasBody)
        m.recompute()
        assertArrayEquals(m.parts[0].base, m.parts[0].cur, 1e-4f)
    }

    @Test
    fun bodySlidersReshapeTheRightArea() {
        val m = model()
        val h0 = top(m)
        val waist0 = width(m, 0, h0 * 0.58f, h0 * 0.64f); val waistD0 = width(m, 0, h0 * 0.58f, h0 * 0.64f, 2)
        val calf0 = width(m, 5, h0 * 0.15f, h0 * 0.25f)

        m.params[EditModel.WAIST] = 1.25f; m.recompute()
        val waist1 = width(m, 0, h0 * 0.58f, h0 * 0.64f)
        println("waist width %.1f → %.1f, depth %.1f → %.1f, worst edge ×%.2f".format(waist0, waist1, waistD0, width(m, 0, h0 * 0.58f, h0 * 0.64f, 2), worstEdge(m)))
        assertTrue(waist1 > waist0 * 1.15f)
        assertTrue(width(m, 0, h0 * 0.58f, h0 * 0.64f, 2) > waistD0 * 1.15f)
        assertEquals(h0, top(m), 0.01f)
        assertEquals(calf0, width(m, 5, h0 * 0.15f, h0 * 0.25f), 0.2f)
        assertTrue(worstEdge(m) < 1.6f)

        m.params[EditModel.WAIST] = 1f
        for (k in listOf(EditModel.SHOULDERS, EditModel.CHEST, EditModel.HIPS, EditModel.ARM_THICK, EditModel.ARM_LEN, EditModel.LEG_THICK, EditModel.HEAD)) {
            m.params[k] = 1.3f; m.recompute()
            val w = worstEdge(m)
            println("slider $k at 130%%: worst edge ×%.2f".format(w))
            assertTrue("slider $k tears the mesh (×$w)", w < 2.6f)
            assertTrue(m.parts[0].cur.all { it.isFinite() })
            m.params[k] = 0.7f; m.recompute()
            assertTrue("slider $k tears the mesh at 70%", worstEdge(m) < 2.6f)
            m.params[k] = 1f
        }

        m.params[EditModel.LEG_THICK] = 1.3f; m.recompute()
        assertTrue(width(m, 5, h0 * 0.15f, h0 * 0.25f) > calf0 * 1.2f)
        m.params[EditModel.LEG_THICK] = 1f

        m.params[EditModel.LEG_LEN] = 1.1f; m.recompute()
        println("leg length 110%%: height %.1f → %.1f, worst edge ×%.2f".format(h0, top(m), worstEdge(m)))
        assertTrue(top(m) > h0 + 5f && top(m) < h0 + 12f)
        assertTrue(worstEdge(m) < 1.7f)
        m.params[EditModel.LEG_LEN] = 1f

        m.params[EditModel.WIDTH] = 1.2f; m.params[EditModel.HEIGHT] = 0.9f; m.recompute()
        assertEquals(h0 * 0.9f, top(m), 0.05f)
        m.resetAll()
        assertArrayEquals(m.parts[0].base, m.parts[0].cur, 1e-4f)
    }

    @Test
    fun brushPushesPullsSmoothsAndUndoes() {
        val m = model()
        val y = top(m) * 0.72f
        val hit = m.pick(floatArrayOf(m.centre[0], y, 500f), floatArrayOf(0f, 0f, -1f))
        assertNotNull(hit); hit!!
        assertTrue("normal should face the viewer", hit.n[2] > 0.3f)
        val before = m.parts[0].cur.copyOf()

        m.beginStroke()
        m.brush(hit, 6f, 2f, EditModel.PUSH)
        val after = m.pick(floatArrayOf(m.centre[0], y, 500f), floatArrayOf(0f, 0f, -1f))!!
        println("push: surface z %.2f → %.2f".format(hit.p[2], after.p[2]))
        assertTrue(after.p[2] > hit.p[2] + 1f)
        // Seam duplicates stayed together.
        val p = m.parts[0]
        for (v in 0 until p.n) { val g = p.group[v]; for (j in 0 until 3) assertEquals(p.cur[g * 3 + j], p.cur[v * 3 + j], 1e-5f) }
        // Only nearby points moved.
        for (v in 0 until p.n) if (abs(before[v * 3 + 1] - y) > 7f) assertEquals(before[v * 3 + 2], p.cur[v * 3 + 2], 1e-6f)

        m.beginStroke()
        repeat(10) { m.brush(after, 6f, 1f, EditModel.SMOOTH) }
        val smoothed = m.pick(floatArrayOf(m.centre[0], y, 500f), floatArrayOf(0f, 0f, -1f))!!
        println("smooth: surface z %.2f → %.2f".format(after.p[2], smoothed.p[2]))
        assertTrue(smoothed.p[2] < after.p[2] - 0.1f)

        m.beginStroke()
        m.brush(hit, 6f, 1f, EditModel.PULL, mirror = true)
        assertTrue(m.undo()); assertTrue(m.undo()); assertTrue(m.undo())
        assertArrayEquals(before, m.parts[0].cur, 1e-4f)
        assertTrue(!m.undo())
    }

    @Test
    fun plainModelsGetWholeModelSlidersOnly() {
        val cube = floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 2f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 2f, 0f, 2f, 2f, 2f, 2f, 0f, 2f, 2f)
        val tris = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 2, 3, 7, 2, 7, 6, 1, 2, 6, 1, 6, 5, 0, 4, 7, 0, 7, 3)
        val m = EditModel(listOf(EditPart("cube", cube, tris, null)))
        assertTrue(!m.hasBody)
        m.params[EditModel.WAIST] = 1.3f; m.params[EditModel.WIDTH] = 1.5f; m.recompute()
        val xs = (0 until 8).map { m.parts[0].cur[it * 3] }
        assertEquals(3f, xs.max() - xs.min(), 1e-4f)
        assertEquals(2f, (0 until 8).maxOf { m.parts[0].cur[it * 3 + 1] }, 1e-4f)
        assertNotNull(m.pick(floatArrayOf(1f, 1f, 10f), floatArrayOf(0f, 0f, -1f)))
        assertEquals(24, m.parts[0].normals().size)
    }
}
