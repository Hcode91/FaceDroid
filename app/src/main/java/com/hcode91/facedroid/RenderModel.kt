package com.hcode91.facedroid

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Triangle soup ready for the GL view: 8 floats per vertex (x y z, nx ny nz, u v), 3 vertices per
 * triangle, already centred and scaled to fit a sphere of radius 10.
 */
class RenderModel(val data: FloatArray, val texture: Bitmap?) {
    val vertexCount get() = data.size / 8
    val triangleCount get() = vertexCount / 3

    companion object {
        const val STRIDE = 8

        fun fromFaceMesh(mesh: FaceMesh, texture: Bitmap): RenderModel {
            val out = FloatArray(mesh.tris.size * STRIDE)
            for ((k, v) in mesh.tris.withIndex()) {
                val o = k * STRIDE
                out[o] = mesh.positions[v * 3]; out[o + 1] = mesh.positions[v * 3 + 1]; out[o + 2] = mesh.positions[v * 3 + 2]
                out[o + 3] = mesh.normals[v * 3]; out[o + 4] = mesh.normals[v * 3 + 1]; out[o + 5] = mesh.normals[v * 3 + 2]
                out[o + 6] = mesh.uvs[v * 2]; out[o + 7] = mesh.uvs[v * 2 + 1]
            }
            normalise(out)
            return RenderModel(out, texture)
        }

        /** Centre on the bounding box and scale so the model fits in radius 10. */
        fun normalise(d: FloatArray) {
            if (d.isEmpty()) return
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var i = 0
            while (i < d.size) {
                for (j in 0 until 3) { min[j] = minOf(min[j], d[i + j]); max[j] = maxOf(max[j], d[i + j]) }
                i += STRIDE
            }
            val c = FloatArray(3) { (min[it] + max[it]) / 2 }
            var r = 0f
            i = 0
            while (i < d.size) {
                val x = d[i] - c[0]; val y = d[i + 1] - c[1]; val z = d[i + 2] - c[2]
                r = maxOf(r, sqrt(x * x + y * y + z * z))
                i += STRIDE
            }
            val s = if (r > 1e-6f) 10f / r else 1f
            i = 0
            while (i < d.size) {
                for (j in 0 until 3) d[i + j] = (d[i + j] - c[j]) * s
                i += STRIDE
            }
        }
    }
}
