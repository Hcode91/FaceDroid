package com.hcode91.facedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeadMeshTest {

    private fun topology(): FaceTopology {
        val f = listOf("src/main/assets/face_topology.txt", "app/src/main/assets/face_topology.txt").map(::File).first { it.exists() }
        val canon = FloatArray(FaceTopology.N * 3); val uv = FloatArray(FaceTopology.N * 2)
        val tris = ArrayList<Int>()
        var mode = ' '; var i = 0
        for (line in f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val p = line.split(' ')
            when {
                p[0] == "V" -> { mode = 'V'; i = 0 }
                p[0] == "T" -> mode = 'T'
                mode == 'V' -> { for (j in 0 until 3) canon[i * 3 + j] = p[j].toFloat(); uv[i * 2] = p[3].toFloat(); uv[i * 2 + 1] = p[4].toFloat(); i++ }
                else -> p.forEach { tris.add(it.toInt()) }
            }
        }
        return FaceTopology(canon, uv, tris.toIntArray())
    }

    @Test
    fun fullHeadIsClosedAndRoundTrips() {
        val topo = topology()
        assertEquals(36, topo.outline.size)
        val lm = LandmarkDetector.templateLandmarks(topo, 1000, 1400)
        val mesh = FaceMesh.build(topo, lm, 1f, fullHead = true)
        assertTrue(mesh.positions.all { it.isFinite() })
        assertTrue(mesh.uvs.all { it in 0f..1f })

        // Every edge (by position) should be shared by exactly two triangles: a closed surface.
        val key = { v: Int -> Triple(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]) }
        val edges = HashMap<Set<Triple<Float, Float, Float>>, Int>()
        for (t in 0 until mesh.triCount) for (j in 0 until 3) {
            val e = setOf(key(mesh.tris[t * 3 + j]), key(mesh.tris[t * 3 + (j + 1) % 3]))
            if (e.size == 2) edges[e] = (edges[e] ?: 0) + 1
        }
        val open = edges.values.count { it != 2 }
        assertEquals("open edges", 0, open)

        val text = ObjExporter.obj(mesh, "test")
        File(System.getProperty("java.io.tmpdir"), "facedroid_test_head.obj").writeText(text)
        val back = ObjLoader.load(text.byteInputStream())
        assertEquals(mesh.triCount * 3 * RenderModel.STRIDE, back.data.size)
        assertTrue(back.hasUvs)
    }
}
