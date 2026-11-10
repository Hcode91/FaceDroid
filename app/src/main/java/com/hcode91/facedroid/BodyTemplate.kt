package com.hcode91.facedroid

import java.io.InputStream

/**
 * The MakeHuman base body (CC0), A-pose, in decimetres: y up, z forward, +x = the person's left.
 * [region] per vertex: 0 torso, 1 head, 2 neck, 3 left arm, 4 right arm, 5 left leg, 6 right leg.
 * [keypoints] maps MediaPipe pose landmark numbers (plus [TOP] for the top of the head) to template positions.
 */
class BodyTemplate(
    val pos: FloatArray,
    val uv: FloatArray,
    val tris: IntArray,
    val region: ByteArray,
    val keypoints: Map<Int, FloatArray>,
) {
    val vertexCount get() = pos.size / 3
    val minY: Float = (0 until vertexCount).minOf { pos[it * 3 + 1] }
    val maxY: Float = (0 until vertexCount).maxOf { pos[it * 3 + 1] }
    val height get() = maxY - minY

    fun kp(id: Int): FloatArray = keypoints[id] ?: error("No template keypoint $id")

    companion object {
        const val TORSO = 0; const val HEAD = 1; const val NECK = 2
        const val ARM_L = 3; const val ARM_R = 4; const val LEG_L = 5; const val LEG_R = 6
        const val TOP = 33

        fun parse(input: InputStream): BodyTemplate {
            var pos = FloatArray(0); var uv = FloatArray(0); var region = ByteArray(0); var tris = IntArray(0)
            val kps = HashMap<Int, FloatArray>()
            var mode = ' '; var i = 0
            input.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    if (raw.isEmpty() || raw[0] == '#') continue
                    val p = raw.trim().split(' ')
                    when (p[0]) {
                        "V" -> { mode = 'V'; i = 0; val n = p[1].toInt(); pos = FloatArray(n * 3); uv = FloatArray(n * 2); region = ByteArray(n) }
                        "T" -> { mode = 'T'; i = 0; tris = IntArray(p[1].toInt() * 3) }
                        "K" -> { mode = 'K' }
                        else -> when (mode) {
                            'V' -> {
                                pos[i * 3] = p[0].toFloat(); pos[i * 3 + 1] = p[1].toFloat(); pos[i * 3 + 2] = p[2].toFloat()
                                uv[i * 2] = p[3].toFloat(); uv[i * 2 + 1] = p[4].toFloat()
                                region[i] = p[5].toByte()
                                i++
                            }
                            'T' -> { tris[i * 3] = p[0].toInt(); tris[i * 3 + 1] = p[1].toInt(); tris[i * 3 + 2] = p[2].toInt(); i++ }
                            'K' -> kps[p[0].toInt()] = floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                        }
                    }
                }
            }
            return BodyTemplate(pos, uv, tris, region, kps)
        }
    }
}
