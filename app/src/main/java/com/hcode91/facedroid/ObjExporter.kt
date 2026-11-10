package com.hcode91.facedroid

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.util.Locale

/** Writes name.obj + name.mtl + name.png into Download/FaceDroid. */
object ObjExporter {

    const val FOLDER = "FaceDroid"

    /** Saves base.obj, base.mtl and one base_<part>.png per textured part. Returns the URIs (obj first). */
    fun export(context: Context, parts: List<MeshPart>, baseName: String): List<Uri> {
        val uris = ArrayList<Uri>()
        uris.add(save(context, "$baseName.obj", "application/octet-stream") { it.write(obj(parts, baseName).toByteArray()) })
        uris.add(save(context, "$baseName.mtl", "application/octet-stream") { it.write(mtl(parts, baseName).toByteArray()) })
        for (p in parts) {
            val tex = p.texture ?: continue
            uris.add(save(context, texName(baseName, p), "image/png") { tex.compress(Bitmap.CompressFormat.PNG, 100, it) })
        }
        return uris
    }

    private fun texName(base: String, p: MeshPart) = "${base}_${p.name}.png"

    fun obj(parts: List<MeshPart>, baseName: String): String {
        val sb = StringBuilder(parts.sumOf { it.positions.size } * 40 + 1000)
        sb.append("# FaceDroid model\n")
        sb.append("mtllib ").append(baseName).append(".mtl\n")
        var vOff = 0
        for (part in parts) {
            sb.append("o ").append(part.name).append('\n')
            val p = part.positions
            for (i in 0 until p.size / 3) sb.append("v ").append(f(p[i * 3])).append(' ').append(f(p[i * 3 + 1])).append(' ').append(f(p[i * 3 + 2])).append('\n')
            val uv = part.uvs
            for (i in 0 until uv.size / 2) sb.append("vt ").append(f(uv[i * 2])).append(' ').append(f(uv[i * 2 + 1])).append('\n')
            val n = part.normals
            for (i in 0 until n.size / 3) sb.append("vn ").append(f(n[i * 3])).append(' ').append(f(n[i * 3 + 1])).append(' ').append(f(n[i * 3 + 2])).append('\n')
            sb.append("usemtl ").append(part.name).append("\ns 1\n")
            val t = part.tris
            for (i in 0 until t.size / 3) {
                sb.append('f')
                for (j in 0 until 3) {
                    val k = t[i * 3 + j] + 1 + vOff
                    sb.append(' ').append(k).append('/').append(k).append('/').append(k)
                }
                sb.append('\n')
            }
            vOff += p.size / 3
        }
        return sb.toString()
    }

    private fun mtl(parts: List<MeshPart>, baseName: String) = buildString {
        for (p in parts) {
            append("newmtl ").append(p.name).append('\n')
            append("Ka 1.000 1.000 1.000\nKd 1.000 1.000 1.000\nKs 0.000 0.000 0.000\nd 1.0\nillum 1\n")
            if (p.texture != null) append("map_Kd ").append(texName(baseName, p)).append('\n')
            append('\n')
        }
    }

    private fun f(x: Float) = String.format(Locale.US, "%.6f", x)

    private fun save(context: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
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
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
