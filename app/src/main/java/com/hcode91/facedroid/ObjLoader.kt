package com.hcode91.facedroid

import java.io.BufferedReader
import java.io.InputStream
import kotlin.math.sqrt

/**
 * Reads Wavefront .obj files: v / vt / vn / f (any polygon size, negative indices, v, v/vt,
 * v//vn, v/vt/vn). Faces without normals get flat ones. Also reads which texture an .mtl uses.
 */
object ObjLoader {

    class Result(val data: FloatArray, val mtlLibs: List<String>, val hasUvs: Boolean)

    fun load(input: InputStream): Result {
        val pos = FloatList(); val tex = FloatList(); val nrm = FloatList()
        val out = FloatList()
        val mtlLibs = ArrayList<String>()
        var anyUv = false
        val fv = IntArray(3); val ft = IntArray(3); val fn = IntArray(3)
        val corners = ArrayList<IntArray>(8)

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.length < 2 || line[0] == '#') continue
                val parts = line.split(WS)
                when (parts[0]) {
                    "v" -> { pos.add(parts[1].toFloat()); pos.add(parts[2].toFloat()); pos.add(parts[3].toFloat()) }
                    "vt" -> { tex.add(parts[1].toFloat()); tex.add(if (parts.size > 2) parts[2].toFloat() else 0f) }
                    "vn" -> { nrm.add(parts[1].toFloat()); nrm.add(parts[2].toFloat()); nrm.add(parts[3].toFloat()) }
                    "mtllib" -> mtlLibs.add(line.substring(6).trim())
                    "f" -> {
                        corners.clear()
                        for (k in 1 until parts.size) {
                            val idx = parts[k].split('/')
                            val v = resolve(idx[0], pos.size / 3)
                            val t = if (idx.size > 1 && idx[1].isNotEmpty()) resolve(idx[1], tex.size / 2) else -1
                            val n = if (idx.size > 2 && idx[2].isNotEmpty()) resolve(idx[2], nrm.size / 3) else -1
                            corners.add(intArrayOf(v, t, n))
                        }
                        // Fan-triangulate polygons.
                        for (k in 1 until corners.size - 1) {
                            val tri = arrayOf(corners[0], corners[k], corners[k + 1])
                            for (j in 0 until 3) { fv[j] = tri[j][0]; ft[j] = tri[j][1]; fn[j] = tri[j][2] }
                            emit(out, pos, tex, nrm, fv, ft, fn)
                            if (ft[0] >= 0) anyUv = true
                        }
                    }
                }
            }
        }
        return Result(out.toArray(), mtlLibs, anyUv)
    }

    /** Texture file names (map_Kd) referenced by an .mtl file. */
    fun textureNames(mtl: InputStream): List<String> {
        val names = ArrayList<String>()
        mtl.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("map_Kd", ignoreCase = true)) {
                    // Options like "-s 1 1 1" may come first; the file name is the last token(s).
                    val name = line.split(WS).last()
                    names.add(name.substringAfterLast('/').substringAfterLast('\\'))
                }
            }
        }
        return names
    }

    private val WS = Regex("\\s+")

    private fun resolve(s: String, count: Int): Int {
        val i = s.toInt()
        return if (i < 0) count + i else i - 1
    }

    private fun emit(out: FloatList, pos: FloatList, tex: FloatList, nrm: FloatList, fv: IntArray, ft: IntArray, fn: IntArray) {
        var fnx = 0f; var fny = 0f; var fnz = 1f
        val needFlat = fn.any { it < 0 || it * 3 + 2 >= nrm.size }
        if (needFlat) {
            val a = fv[0] * 3; val b = fv[1] * 3; val c = fv[2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            fnx = uy * vz - uz * vy; fny = uz * vx - ux * vz; fnz = ux * vy - uy * vx
            val l = sqrt(fnx * fnx + fny * fny + fnz * fnz)
            if (l > 1e-12f) { fnx /= l; fny /= l; fnz /= l } else { fnx = 0f; fny = 0f; fnz = 1f }
        }
        for (j in 0 until 3) {
            val v = fv[j] * 3
            out.add(pos[v]); out.add(pos[v + 1]); out.add(pos[v + 2])
            if (needFlat) { out.add(fnx); out.add(fny); out.add(fnz) }
            else { val n = fn[j] * 3; out.add(nrm[n]); out.add(nrm[n + 1]); out.add(nrm[n + 2]) }
            val t = ft[j]
            if (t >= 0 && t * 2 + 1 < tex.size) { out.add(tex[t * 2]); out.add(tex[t * 2 + 1]) } else { out.add(0f); out.add(0f) }
        }
    }

    /** Growable float array without boxing. */
    private class FloatList {
        private var a = FloatArray(1024)
        var size = 0; private set
        fun add(f: Float) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = f
        }
        operator fun get(i: Int) = a[i]
        fun toArray() = a.copyOf(size)
    }
}
