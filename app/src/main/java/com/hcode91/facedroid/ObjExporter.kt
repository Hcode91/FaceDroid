package com.hcode91.facedroid

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.util.Locale

/** Writes name.obj + name.mtl + name.png into Download/FaceDroid. */
object ObjExporter {

    const val FOLDER = "FaceDroid"

    fun export(context: Context, mesh: FaceMesh, texture: Bitmap, baseName: String) {
        save(context, "$baseName.png", "image/png") { texture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        save(context, "$baseName.mtl", "application/octet-stream") { it.write(mtl(baseName).toByteArray()) }
        save(context, "$baseName.obj", "application/octet-stream") { it.write(obj(mesh, baseName).toByteArray()) }
    }

    fun obj(mesh: FaceMesh, baseName: String): String {
        val sb = StringBuilder(200_000)
        sb.append("# FaceDroid face mesh\n")
        sb.append("mtllib ").append(baseName).append(".mtl\n")
        sb.append("o face\n")
        val p = mesh.positions
        for (i in 0 until p.size / 3) sb.append("v ").append(f(p[i * 3])).append(' ').append(f(p[i * 3 + 1])).append(' ').append(f(p[i * 3 + 2])).append('\n')
        val uv = mesh.uvs
        for (i in 0 until uv.size / 2) sb.append("vt ").append(f(uv[i * 2])).append(' ').append(f(uv[i * 2 + 1])).append('\n')
        val n = mesh.normals
        for (i in 0 until n.size / 3) sb.append("vn ").append(f(n[i * 3])).append(' ').append(f(n[i * 3 + 1])).append(' ').append(f(n[i * 3 + 2])).append('\n')
        sb.append("usemtl face\ns 1\n")
        val t = mesh.tris
        for (i in 0 until t.size / 3) {
            sb.append('f')
            for (j in 0 until 3) {
                val k = t[i * 3 + j] + 1
                sb.append(' ').append(k).append('/').append(k).append('/').append(k)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun mtl(baseName: String) = """
        |newmtl face
        |Ka 1.000 1.000 1.000
        |Kd 1.000 1.000 1.000
        |Ks 0.000 0.000 0.000
        |d 1.0
        |illum 1
        |map_Kd $baseName.png
        |""".trimMargin()

    private fun f(x: Float) = String.format(Locale.US, "%.6f", x)

    private fun save(context: Context, name: String, mime: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create $name in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
