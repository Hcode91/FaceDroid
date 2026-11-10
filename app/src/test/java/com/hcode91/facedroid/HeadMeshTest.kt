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

        val text = ObjExporter.obj(listOf(MeshPart("head", mesh.positions, mesh.normals, mesh.uvs, mesh.tris, null)), "test")
        File(System.getProperty("java.io.tmpdir"), "facedroid_test_head.obj").writeText(text)
        val back = ObjLoader.load(text.byteInputStream())
        assertEquals(mesh.triCount, back.triangleCount)
        assertTrue(back.hasUvs)
    }

    @Test
    fun sidePhotoReshapesSkull() {
        val topo = topology()
        val lm = LandmarkDetector.templateLandmarks(topo, 1000, 1400)
        val plain = FaceMesh.build(topo, lm, 1f, fullHead = true)
        // Fake side photo: project the plain head (facing left), then raise the top and push the back out.
        val map = { y: Float, z: Float -> floatArrayOf(500f - z * 20f, 600f - y * 20f) }
        fun pin(v: Int) = map(plain.positions[v * 3 + 1], plain.positions[v * 3 + 2])
        var topV = 0; var backV = 0
        for (v in 468 until plain.vertexCount) {
            if (plain.positions[v * 3 + 1] > plain.positions[topV * 3 + 1]) topV = v
            if (plain.positions[v * 3 + 2] < plain.positions[backV * 3 + 2]) backV = v
        }
        val top = pin(topV).also { it[1] -= 20f }      // 1 unit higher
        val backP = pin(backV).also { it[0] += 30f }   // 1.5 units further back
        val ear = map(plain.positions[234 * 3 + 1], plain.positions[234 * 3 + 2])
        val pins = pin(1) + pin(152) + top + backP + ear
        val side = FaceMesh.build(topo, lm, 1f, fullHead = true, side = FaceMesh.SideInput(pins, 1000, 1400))
        assertTrue(side.positions.all { it.isFinite() })
        val maxY = { m: FaceMesh -> (468 until m.vertexCount).maxOf { m.positions[it * 3 + 1] } }
        val minZ = { m: FaceMesh -> (468 until m.vertexCount).minOf { m.positions[it * 3 + 2] } }
        assertEquals(maxY(plain) + 1f, maxY(side), 0.15f)
        assertEquals(minZ(plain) - 1.5f, minZ(side), 0.2f)
        assertTrue(side.sidePx != null && side.triView.any { it.toInt() == 2 })
    }
}
