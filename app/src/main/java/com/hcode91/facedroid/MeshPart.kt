package com.hcode91.facedroid

import android.graphics.Bitmap

/** One textured piece of a model (e.g. "body" or "head"). Positions in cm, y up, z forward. */
class MeshPart(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val tris: IntArray,
    val texture: Bitmap?,
) {
    companion object {
        fun from(mesh: FaceMesh, texture: Bitmap) =
            MeshPart(if (mesh.fullHead) "head" else "face", mesh.positions, mesh.normals, mesh.uvs, mesh.tris, texture)
    }
}
