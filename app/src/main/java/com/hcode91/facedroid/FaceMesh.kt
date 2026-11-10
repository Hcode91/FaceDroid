package com.hcode91.facedroid

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A finished face or head. Positions are roughly centimetres (y up, z towards viewer).
 *
 * [srcPx] is where each vertex sits in the photo (x, y pixels) and [bakeFromPhoto] says which
 * triangles can be textured from the photo; the rest of the texture is filled with [fillHint]s.
 */
class FaceMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val tris: IntArray,
    val srcPx: FloatArray,
    val bakeFromPhoto: BooleanArray,
    val texWidth: Int,
    val texHeight: Int,
    val fullHead: Boolean,
    /** Photo pixel to sample the hair/scalp colour from (full head only). */
    val hairSampleX: Float,
    val hairSampleY: Float,
) {
    val vertexCount get() = positions.size / 3
    val triCount get() = tris.size / 3

    companion object {
        // Skull ellipsoid in canonical-face units, tuned so the face outline sits on it.
        private val HEAD_CENTRE = floatArrayOf(0f, 3.2f, -5.5f)
        private val HEAD_AXES = floatArrayOf(7.9f, 11.2f, 10.3f)
        private const val RINGS = 14

        /**
         * Turns pixel landmarks (x, y, z per point) into a 3D mesh.
         * [depthScale] exaggerates or flattens the depth (1 = as detected).
         */
        fun build(topo: FaceTopology, lm: FloatArray, depthScale: Float = 1f, fullHead: Boolean = true): FaceMesh {
            val n = FaceTopology.N
            val eyePx = hypot(
                lm[FaceTopology.R_EYE_OUTER * 3] - lm[FaceTopology.L_EYE_OUTER * 3],
                lm[FaceTopology.R_EYE_OUTER * 3 + 1] - lm[FaceTopology.L_EYE_OUTER * 3 + 1]
            )
            val s = (eyePx / topo.canonEyeDistance).coerceAtLeast(1e-3f)

            var cx = 0f; var cy = 0f; var cz = 0f
            for (i in 0 until n) { cx += lm[i * 3]; cy += lm[i * 3 + 1]; cz += lm[i * 3 + 2] }
            cx /= n; cy /= n; cz /= n

            val face = FloatArray(n * 3)
            for (i in 0 until n) {
                face[i * 3] = (lm[i * 3] - cx) / s
                face[i * 3 + 1] = -(lm[i * 3 + 1] - cy) / s
                face[i * 3 + 2] = -(lm[i * 3 + 2] - cz) / s * depthScale
            }
            val faceSrc = FloatArray(n * 2) { if (it % 2 == 0) lm[(it / 2) * 3] else lm[(it / 2) * 3 + 1] }

            if (!fullHead) {
                return FaceMesh(
                    face, computeNormals(face, topo.tris), topo.uv.copyOf(), topo.tris, faceSrc,
                    BooleanArray(topo.triCount) { true }, 2048, 2048, false, 0f, 0f
                )
            }

            // ---- full head: grow a skull from the face outline round to the back of the head ----
            val loop = topo.outline
            val cols = loop.size + 1 // last column repeats the first so the UVs can wrap
            val shellCount = (RINGS + 1) * cols
            val total = n + shellCount

            // Least-squares affine map from the canonical face to this face, so the skull follows
            // the photo's head size, proportions and tilt.
            val aff = fitAffine(topo.canon, face, n)

            val pos = FloatArray(total * 3)
            face.copyInto(pos)
            val uv = FloatArray(total * 2)
            for (i in 0 until n) { uv[i * 2] = topo.uv[i * 2] * 0.5f; uv[i * 2 + 1] = topo.uv[i * 2 + 1] }

            val c = HEAD_CENTRE; val a = HEAD_AXES
            val q = FloatArray(3); val d = FloatArray(3); val p = FloatArray(3); val m = FloatArray(3)
            for (col in 0 until cols) {
                val b = loop[col % loop.size]
                // Residual between the actual face edge and where the affine map puts it; fades out going back.
                applyAffine(aff, topo.canon[b * 3], topo.canon[b * 3 + 1], topo.canon[b * 3 + 2], m)
                val rx = face[b * 3] - m[0]; val ry = face[b * 3 + 1] - m[1]; val rz = face[b * 3 + 2] - m[2]

                for (k in 0..RINGS) {
                    val t = k.toFloat() / RINGS
                    for (j in 0 until 3) q[j] = (topo.canon[b * 3 + j] - c[j]) / a[j]
                    val r0 = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
                    for (j in 0 until 3) q[j] /= r0
                    slerpToBack(q, t, d)
                    val sm = smooth(minOf(1f, t * 2.5f))
                    val r = r0 * (1 - sm) + sm
                    for (j in 0 until 3) p[j] = c[j] + a[j] * d[j] * r
                    applyAffine(aff, p[0], p[1], p[2], m)
                    val w = 1f - smooth(minOf(1f, t / 0.35f))
                    val vi = n + k * cols + col
                    pos[vi * 3] = m[0] + rx * w
                    pos[vi * 3 + 1] = m[1] + ry * w
                    pos[vi * 3 + 2] = m[2] + rz * w
                    uv[vi * 2] = 0.505f + 0.49f * col / (cols - 1)
                    uv[vi * 2 + 1] = 0.995f - 0.99f * t
                }
            }
            // Ring 0 must sit exactly on the face edge (no cracks).
            for (col in 0 until cols) {
                val b = loop[col % loop.size]; val vi = n + col
                pos[vi * 3] = face[b * 3]; pos[vi * 3 + 1] = face[b * 3 + 1]; pos[vi * 3 + 2] = face[b * 3 + 2]
            }

            val shellTris = ArrayList<Int>()
            for (k in 0 until RINGS) for (col in 0 until cols - 1) {
                val i0 = n + k * cols + col; val j0 = i0 + 1
                val i1 = i0 + cols; val j1 = j0 + cols
                shellTris.add(i0); shellTris.add(i1); shellTris.add(j0)
                if (k < RINGS - 1) { shellTris.add(j0); shellTris.add(i1); shellTris.add(j1) }
            }
            val tris = IntArray(topo.tris.size + shellTris.size)
            topo.tris.copyInto(tris)
            for (i in shellTris.indices) tris[topo.tris.size + i] = shellTris[i]

            // Where each vertex lands in the photo (front-on projection).
            val src = FloatArray(total * 2)
            faceSrc.copyInto(src)
            for (i in n until total) {
                src[i * 2] = pos[i * 3] * s + cx
                src[i * 2 + 1] = -pos[i * 3 + 1] * s + cy
            }

            val normals = computeNormals(pos, tris)
            // Only skull triangles that clearly face the camera get photo pixels (e.g. the hairline).
            val bake = BooleanArray(tris.size / 3) { t ->
                if (t < topo.triCount) true else {
                    var nz = 0f
                    for (j in 0 until 3) nz += normals[tris[t * 3 + j] * 3 + 2]
                    nz / 3f > 0.35f
                }
            }

            // Sample hair colour a little above the top of the forehead.
            var topCol = 0
            for (col in 0 until loop.size) if (face[loop[col] * 3 + 1] > face[loop[topCol] * 3 + 1]) topCol = col
            val hv = n + 2 * cols + topCol
            return FaceMesh(pos, normals, uv, tris, src, bake, 4096, 2048, true, src[hv * 2], src[hv * 2 + 1])
        }

        private fun smooth(x: Float) = x * x * (3 - 2 * x)

        /** Rotate unit vector [u] towards the back of the head (0, 0, -1) by fraction [t]. */
        private fun slerpToBack(u: FloatArray, t: Float, out: FloatArray) {
            val dot = (-u[2]).coerceIn(-1f, 1f)
            val om = acos(dot)
            if (om < 1e-4f) { u.copyInto(out); return }
            val so = sin(om)
            val wa = sin((1 - t) * om) / so
            val wb = sin(t * om) / so
            out[0] = u[0] * wa; out[1] = u[1] * wa; out[2] = u[2] * wa - wb
        }

        /** Fits dst ≈ M · [src, 1]. Returns 12 numbers (3 rows of 4). */
        private fun fitAffine(src: FloatArray, dst: FloatArray, n: Int): DoubleArray {
            val ata = Array(4) { DoubleArray(4) }
            val atb = Array(3) { DoubleArray(4) }
            val r = DoubleArray(4)
            for (i in 0 until n) {
                r[0] = src[i * 3].toDouble(); r[1] = src[i * 3 + 1].toDouble(); r[2] = src[i * 3 + 2].toDouble(); r[3] = 1.0
                for (x in 0 until 4) {
                    for (y in 0 until 4) ata[x][y] += r[x] * r[y]
                    for (d in 0 until 3) atb[d][x] += r[x] * dst[i * 3 + d]
                }
            }
            val out = DoubleArray(12)
            for (d in 0 until 3) solve4(ata, atb[d]).copyInto(out, d * 4)
            return out
        }

        private fun applyAffine(m: DoubleArray, x: Float, y: Float, z: Float, out: FloatArray) {
            for (d in 0 until 3) out[d] = (m[d * 4] * x + m[d * 4 + 1] * y + m[d * 4 + 2] * z + m[d * 4 + 3]).toFloat()
        }

        private fun solve4(src: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
            val a = Array(4) { src[it].copyOf() }
            val x = rhs.copyOf()
            for (col in 0 until 4) {
                var piv = col
                for (r in col + 1 until 4) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                val tx = x[col]; x[col] = x[piv]; x[piv] = tx
                for (r in col + 1 until 4) {
                    val f = a[r][col] / a[col][col]
                    for (c in col until 4) a[r][c] -= f * a[col][c]
                    x[r] -= f * x[col]
                }
            }
            for (r in 3 downTo 0) {
                var s = x[r]
                for (c in r + 1 until 4) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return x
        }

        /** Smooth normals. Vertices sharing a position (UV seams) share a normal so no lighting seams show. */
        fun computeNormals(pos: FloatArray, tris: IntArray): FloatArray {
            val nv = pos.size / 3
            val group = IntArray(nv)
            val byPos = HashMap<Triple<Float, Float, Float>, Int>()
            for (i in 0 until nv) group[i] = byPos.getOrPut(Triple(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])) { i }

            val acc = FloatArray(pos.size)
            for (t in 0 until tris.size / 3) {
                val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(tris[t * 3], tris[t * 3 + 1], tris[t * 3 + 2])) {
                    val g = group[v] * 3
                    acc[g] += nx; acc[g + 1] += ny; acc[g + 2] += nz
                }
            }
            val out = FloatArray(pos.size)
            for (i in 0 until nv) {
                val g = group[i] * 3
                val l = sqrt(acc[g] * acc[g] + acc[g + 1] * acc[g + 1] + acc[g + 2] * acc[g + 2])
                if (l > 1e-8f) { out[i * 3] = acc[g] / l; out[i * 3 + 1] = acc[g + 1] / l; out[i * 3 + 2] = acc[g + 2] / l } else out[i * 3 + 2] = 1f
            }
            return out
        }
    }
}
