package com.hcode91.facedroid

import kotlin.math.hypot
import kotlin.math.sqrt

/** A finished face: positions in roughly centimetres (y up, z towards viewer), per-vertex normals and UVs. */
class FaceMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val tris: IntArray,
) {
    companion object {
        /**
         * Turns pixel landmarks (x, y, z per point) into a 3D mesh.
         * [depthScale] exaggerates or flattens the depth (1 = as detected).
         */
        fun build(topo: FaceTopology, lm: FloatArray, depthScale: Float = 1f): FaceMesh {
            val n = FaceTopology.N
            val eyePx = hypot(
                lm[FaceTopology.R_EYE_OUTER * 3] - lm[FaceTopology.L_EYE_OUTER * 3],
                lm[FaceTopology.R_EYE_OUTER * 3 + 1] - lm[FaceTopology.L_EYE_OUTER * 3 + 1]
            )
            val s = (eyePx / topo.canonEyeDistance).coerceAtLeast(1e-3f)

            var cx = 0f; var cy = 0f; var cz = 0f
            for (i in 0 until n) { cx += lm[i * 3]; cy += lm[i * 3 + 1]; cz += lm[i * 3 + 2] }
            cx /= n; cy /= n; cz /= n

            val pos = FloatArray(n * 3)
            for (i in 0 until n) {
                pos[i * 3] = (lm[i * 3] - cx) / s
                pos[i * 3 + 1] = -(lm[i * 3 + 1] - cy) / s
                pos[i * 3 + 2] = -(lm[i * 3 + 2] - cz) / s * depthScale
            }
            return FaceMesh(pos, computeNormals(pos, topo.tris), topo.uv.copyOf(), topo.tris)
        }

        private fun computeNormals(pos: FloatArray, tris: IntArray): FloatArray {
            val nrm = FloatArray(pos.size)
            for (t in 0 until tris.size / 3) {
                val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (k in intArrayOf(a, b, c)) { nrm[k] += nx; nrm[k + 1] += ny; nrm[k + 2] += nz }
            }
            for (i in 0 until nrm.size / 3) {
                val l = sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
                if (l > 1e-8f) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l } else nrm[i * 3 + 2] = 1f
            }
            return nrm
        }
    }
}
