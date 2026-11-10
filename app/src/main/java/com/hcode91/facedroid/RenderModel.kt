package com.hcode91.facedroid

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Triangle soup ready for the GL view, one [Part] per texture: 8 floats per vertex
 * (x y z, nx ny nz, u v), 3 vertices per triangle, centred and scaled to fit radius 10.
 */
class RenderModel(val parts: List<Part>) {
    class Part(val data: FloatArray, val texture: Bitmap?) {
        val vertexCount get() = data.size / STRIDE
    }

    val triangleCount get() = parts.sumOf { it.vertexCount } / 3

    companion object {
        const val STRIDE = 8

        fun fromParts(meshes: List<MeshPart>): RenderModel {
            val parts = meshes.map { mesh ->
                val out = FloatArray(mesh.tris.size * STRIDE)
                for ((k, v) in mesh.tris.withIndex()) {
                    val o = k * STRIDE
                    out[o] = mesh.positions[v * 3]; out[o + 1] = mesh.positions[v * 3 + 1]; out[o + 2] = mesh.positions[v * 3 + 2]
                    out[o + 3] = mesh.normals[v * 3]; out[o + 4] = mesh.normals[v * 3 + 1]; out[o + 5] = mesh.normals[v * 3 + 2]
                    out[o + 6] = mesh.uvs[v * 2]; out[o + 7] = mesh.uvs[v * 2 + 1]
                }
                Part(out, mesh.texture)
            }
            normalise(parts.map { it.data })
            return RenderModel(parts)
        }

        /** Centre all parts on their combined bounding box and scale to fit radius 10. */
        fun normalise(all: List<FloatArray>) {
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (d in all) { var i = 0; while (i < d.size) { for (j in 0 until 3) { min[j] = minOf(min[j], d[i + j]); max[j] = maxOf(max[j], d[i + j]) }; i += STRIDE } }
            if (min[0] > max[0]) return
            val c = FloatArray(3) { (min[it] + max[it]) / 2 }
            var r = 0f
            for (d in all) { var i = 0; while (i < d.size) { val x = d[i] - c[0]; val y = d[i + 1] - c[1]; val z = d[i + 2] - c[2]; r = maxOf(r, sqrt(x * x + y * y + z * z)); i += STRIDE } }
            val s = if (r > 1e-6f) 10f / r else 1f
            for (d in all) { var i = 0; while (i < d.size) { for (j in 0 until 3) d[i + j] = (d[i + j] - c[j]) * s; i += STRIDE } }
        }
    }
}
