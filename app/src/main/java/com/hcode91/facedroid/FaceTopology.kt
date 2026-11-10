package com.hcode91.facedroid

import android.content.Context
import kotlin.math.hypot

/**
 * The fixed face mesh layout from MediaPipe's canonical face model:
 * 468 vertices in landmark order, their UV coordinates (v = 0 at bottom),
 * and 898 counter-clockwise triangles.
 */
class FaceTopology(
    val canon: FloatArray, // N*3, canonical positions (roughly cm, y up, z towards viewer)
    val uv: FloatArray,    // N*2
    val tris: IntArray,    // T*3
) {
    val triCount get() = tris.size / 3

    /** Unique edges as index pairs, for drawing the wireframe. */
    val edges: IntArray by lazy {
        val seen = HashSet<Long>()
        val out = ArrayList<Int>()
        for (t in 0 until triCount) for (j in 0 until 3) {
            val a = tris[t * 3 + j]
            val b = tris[t * 3 + (j + 1) % 3]
            val key = minOf(a, b).toLong() * 100000 + maxOf(a, b)
            if (seen.add(key)) { out.add(a); out.add(b) }
        }
        out.toIntArray()
    }

    /** Distance between the outer eye corners in canonical units. */
    val canonEyeDistance: Float
        get() = hypot(canon[R_EYE_OUTER * 3] - canon[L_EYE_OUTER * 3], canon[R_EYE_OUTER * 3 + 1] - canon[L_EYE_OUTER * 3 + 1])

    companion object {
        const val N = 468
        const val R_EYE_OUTER = 33
        const val L_EYE_OUTER = 263

        fun load(context: Context): FaceTopology {
            val canon = FloatArray(N * 3)
            val uv = FloatArray(N * 2)
            var tris = IntArray(0)
            var mode = ' '
            var i = 0
            context.assets.open("face_topology.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val p = line.split(' ')
                    when {
                        p[0] == "V" -> { mode = 'V'; i = 0 }
                        p[0] == "T" -> { mode = 'T'; i = 0; tris = IntArray(p[1].toInt() * 3) }
                        mode == 'V' -> {
                            canon[i * 3] = p[0].toFloat(); canon[i * 3 + 1] = p[1].toFloat(); canon[i * 3 + 2] = p[2].toFloat()
                            uv[i * 2] = p[3].toFloat(); uv[i * 2 + 1] = p[4].toFloat()
                            i++
                        }
                        mode == 'T' -> {
                            tris[i * 3] = p[0].toInt(); tris[i * 3 + 1] = p[1].toInt(); tris[i * 3 + 2] = p[2].toInt()
                            i++
                        }
                    }
                }
            }
            return FaceTopology(canon, uv, tris)
        }
    }
}
