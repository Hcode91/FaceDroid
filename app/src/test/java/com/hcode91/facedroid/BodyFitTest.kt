package com.hcode91.facedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

class BodyFitTest {

    private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }
    private val t = asset("body_template.txt").inputStream().use { BodyTemplate.parse(it) }

    /** "Photo" of the template with a known camera: px per dm, and extra width/depth to see if the outline fit finds it. */
    private class Cam(val axis: Int, val sign: Float, val cx: Float, val ground: Float, val k: Float, val widen: Float)

    private fun project(c: Cam, x: Float, y: Float, z: Float, tm: BodyTemplate): FloatArray {
        val a = if (c.axis == 0) x else z
        // Widen the torso and legs (not the arms/head) around the body's centre line.
        return floatArrayOf(c.cx + c.sign * a * c.k * c.widen, c.ground - (y - tm.minY) * c.k)
    }

    private fun pins(view: BodyPins.View, c: Cam): FloatArray {
        val ids = BodyPins.ids(view)
        return FloatArray(ids.size * 2) { i -> val k = t.kp(ids[i / 2]); project(c, k[0], k[1], k[2], t)[i % 2] }
    }

    private fun mask(c: Cam, w: Int, h: Int): Mask {
        val data = ByteArray(w * h)
        val p = t.pos
        for (i in 0 until t.tris.size / 3) {
            val q = IntArray(3) { t.tris[i * 3 + it] }
            val pts = q.map { v ->
                val r = t.region[v].toInt()
                val widen = if (r == BodyTemplate.TORSO || r == BodyTemplate.LEG_L || r == BodyTemplate.LEG_R) c.widen else 1f
                project(Cam(c.axis, c.sign, c.cx, c.ground, c.k, widen), p[v * 3], p[v * 3 + 1], p[v * 3 + 2], t)
            }
            val x0 = pts.minOf { it[0] }.toInt().coerceAtLeast(0); val x1 = ceil(pts.maxOf { it[0] }).toInt().coerceAtMost(w - 1)
            val y0 = pts.minOf { it[1] }.toInt().coerceAtLeast(0); val y1 = ceil(pts.maxOf { it[1] }).toInt().coerceAtMost(h - 1)
            val (a, b, cc) = pts
            val d = (b[1] - cc[1]) * (a[0] - cc[0]) + (cc[0] - b[0]) * (a[1] - cc[1])
            if (abs(d) < 1e-9f) continue
            for (y in y0..y1) for (x in x0..x1) {
                val fx = x + 0.5f; val fy = y + 0.5f
                val w0 = ((b[1] - cc[1]) * (fx - cc[0]) + (cc[0] - b[0]) * (fy - cc[1])) / d
                val w1 = ((cc[1] - a[1]) * (fx - cc[0]) + (a[0] - cc[0]) * (fy - cc[1])) / d
                if (w0 >= 0 && w1 >= 0 && 1 - w0 - w1 >= 0) data[y * w + x] = 1
            }
        }
        return Mask(w, h, data)
    }

    private fun torsoWidth(pos: FloatArray, axis: Int, yLo: Float, yHi: Float, region: Int = BodyTemplate.TORSO): Float {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in 0 until t.vertexCount) {
            if (t.region[v].toInt() != region) continue
            val y = pos[v * 3 + 1]
            if (y < yLo || y > yHi) continue
            lo = minOf(lo, pos[v * 3 + axis]); hi = maxOf(hi, pos[v * 3 + axis])
        }
        return hi - lo
    }

    @Test
    fun fitsHeightWidthAndDepth() {
        val w = 1000; val h = 1600
        val k = 90f
        val front = Cam(0, 1f, 500f, 1550f, k, 1.15f)
        val side = Cam(2, 1f, 500f, 1550f, k, 1.2f)
        val back = Cam(0, -1f, 500f, 1550f, k, 1.15f)
        val res = BodyFitter(t).fit(
            BodyFitter.ViewInput(BodyPins.View.FRONT, pins(BodyPins.View.FRONT, front), w, h, mask(front, w, h)),
            BodyFitter.ViewInput(BodyPins.View.SIDE, pins(BodyPins.View.SIDE, side), w, h, mask(side, w, h)),
            BodyFitter.ViewInput(BodyPins.View.BACK, pins(BodyPins.View.BACK, back), w, h, mask(back, w, h)),
            180f, null
        )
        val p = res.positions
        val text = ObjExporter.obj(listOf(MeshPart("body", p, res.normals, t.uv, res.tris, null)), "body")
        File(System.getProperty("java.io.tmpdir"), "facedroid_test_body.obj").writeText(text)
        File(System.getProperty("java.io.tmpdir"), "facedroid_test_body_views.txt").writeText(res.triView.joinToString("\n"))
        assertTrue(p.all { it.isFinite() })
        val minY = (0 until t.vertexCount).minOf { p[it * 3 + 1] }
        val maxY = (0 until t.vertexCount).maxOf { p[it * 3 + 1] }
        assertEquals("height", 180f, maxY - minY, 3f)

        val cm = 180f / t.height
        // Waist band in cm: template waist ~ y 2.0..3.0 dm above the template's centre.
        val yLo = (2.0f - t.minY) * cm; val yHi = (3.0f - t.minY) * cm
        val tmplW = torsoWidth(t.pos, 0, 2.0f, 3.0f) * cm
        val tmplD = torsoWidth(t.pos, 2, 2.0f, 3.0f) * cm
        val gotW = torsoWidth(p, 0, yLo, yHi)
        val gotD = torsoWidth(p, 2, yLo, yHi)
        println("waist width template %.1f → fitted %.1f (want %.1f); depth %.1f → %.1f (want %.1f)".format(tmplW, gotW, tmplW * 1.15f, tmplD, gotD, tmplD * 1.2f))
        assertEquals("waist width", tmplW * 1.15f, gotW, tmplW * 0.05f)
        assertEquals("waist depth", tmplD * 1.2f, gotD, tmplD * 0.07f)

        // Surface area textured by each photo.
        val area = DoubleArray(4)
        for (i in 0 until res.tris.size / 3) {
            val a = res.tris[i * 3]; val b = res.tris[i * 3 + 1]; val c = res.tris[i * 3 + 2]
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            area[res.triView[i] + 1] += 0.5 * kotlin.math.sqrt((cx * cx + cy * cy + cz * cz).toDouble())
        }
        val total = area.sum()
        println("area: none %.0f%%, front %.0f%%, side %.0f%%, back %.0f%%".format(*area.map { it / total * 100 }.toTypedArray()))
        assertTrue(area[0] / total < 0.12 && area[1] / total > 0.25 && area[2] / total > 0.15 && area[3] / total > 0.25)

    }

    @Test
    fun attachesFaceDroidHead() {
        val topoFile = asset("face_topology.txt")
        val canon = FloatArray(468 * 3); val uv = FloatArray(468 * 2); val tris = ArrayList<Int>()
        var mode = ' '; var i = 0
        for (line in topoFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val q = line.split(' ')
            when {
                q[0] == "V" -> { mode = 'V'; i = 0 }
                q[0] == "T" -> mode = 'T'
                mode == 'V' -> { for (j in 0 until 3) canon[i * 3 + j] = q[j].toFloat(); uv[i * 2] = q[3].toFloat(); uv[i * 2 + 1] = q[4].toFloat(); i++ }
                else -> q.forEach { tris.add(it.toInt()) }
            }
        }
        val topo = FaceTopology(canon, uv, tris.toIntArray())
        val head = FaceMesh.build(topo, LandmarkDetector.templateLandmarks(topo, 1000, 1400), 1f, true)
        val w = 1000; val h = 1600
        val front = Cam(0, 1f, 500f, 1550f, 90f, 1f)
        val res = BodyFitter(t).fit(
            BodyFitter.ViewInput(BodyPins.View.FRONT, pins(BodyPins.View.FRONT, front), w, h, null), null, null,
            175f, BodyFitter.HeadInput(head.positions, head.tris)
        )
        val hp = res.headPositions!!
        assertTrue(hp.all { it.isFinite() } && res.positions.all { it.isFinite() })
        // No template-head triangles left.
        for (v in res.tris) assertTrue(t.region[v].toInt() != BodyTemplate.HEAD)
        val headTop = (0 until hp.size / 3).maxOf { hp[it * 3 + 1] }
        println("head top at %.1f cm".format(headTop))
        assertEquals(175f, headTop, 8f)
        val text = ObjExporter.obj(listOf(
            MeshPart("body", res.positions, res.normals, t.uv, res.tris, null),
            MeshPart("head", hp, res.headNormals!!, head.uvs, head.tris, null)), "bodyhead")
        File(System.getProperty("java.io.tmpdir"), "facedroid_test_bodyhead.obj").writeText(text)
        assertEquals(res.tris.size / 3 + head.triCount, ObjLoader.load(text.byteInputStream()).triangleCount)
    }
}
