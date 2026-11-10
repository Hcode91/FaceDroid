package com.hcode91.facedroid

import android.graphics.Color

/** Pins for a side-on (profile) photo of the head, and the maths linking it to the head mesh. */
object SideProfile {
    const val NOSE = 0
    const val CHIN = 1
    const val TOP = 2
    const val BACK = 3
    const val EAR = 4

    val PINS = listOf(
        PointPinView.Pin("Nose tip"),
        PointPinView.Pin("Chin"),
        PointPinView.Pin("Top of head", Color.rgb(120, 200, 255)),
        PointPinView.Pin("Back of head", Color.rgb(120, 200, 255)),
        PointPinView.Pin("Ear (centre)", Color.rgb(255, 120, 200)),
    )
    val LINES = intArrayOf(NOSE, CHIN, NOSE, EAR, TOP, BACK)

    /** A rough profile in the middle of the photo, facing left. Drag the pins onto the real features. */
    fun defaultPoints(w: Int, h: Int): FloatArray {
        val s = minOf(w.toFloat(), h * 0.75f) * 0.5f
        val cx = w / 2f; val cy = h / 2f
        fun p(x: Float, y: Float) = floatArrayOf(cx + x * s, cy + y * s)
        return p(-0.55f, 0.05f) + p(-0.40f, 0.55f) + p(0.05f, -0.75f) + p(0.55f, -0.10f) + p(0.15f, 0.05f)
    }

    fun flip(points: FloatArray, w: Int) = FloatArray(points.size) { if (it % 2 == 0) w - points[it] else points[it] }

    /**
     * Similarity transform between the head mesh's side view (z forward, y up) and the side photo,
     * pinned at the nose tip and chin. Both sides of the head use the same photo (mirrored).
     */
    class Mapping(pins: FloatArray, pos: FloatArray) {
        /** +1 if the face points right in the photo. */
        private val f = if (pins[NOSE * 2] >= pins[EAR * 2]) 1f else -1f
        private val ax = pins[NOSE * 2]; private val ay = pins[NOSE * 2 + 1]
        private val qax = f * pos[1 * 3 + 2]; private val qay = -pos[1 * 3 + 1]       // landmark 1 = nose tip
        private val sr: Float
        private val si: Float

        init {
            val bx = pins[CHIN * 2] - ax; val by = pins[CHIN * 2 + 1] - ay
            val qbx = f * pos[152 * 3 + 2] - qax; val qby = -pos[152 * 3 + 1] - qay // landmark 152 = chin
            val d = (qbx * qbx + qby * qby).coerceAtLeast(1e-6f)
            sr = (bx * qbx + by * qby) / d
            si = (by * qbx - bx * qby) / d
        }

        fun toPhoto(y: Float, z: Float, out: FloatArray, off: Int) {
            val qx = f * z - qax; val qy = -y - qay
            out[off] = ax + sr * qx - si * qy
            out[off + 1] = ay + si * qx + sr * qy
        }

        /** Photo pixel → (y, z) in mesh space. */
        private fun toMesh(px: Float, py: Float): FloatArray {
            val dx = px - ax; val dy = py - ay
            val d = (sr * sr + si * si).coerceAtLeast(1e-9f)
            val qx = (dx * sr + dy * si) / d + qax
            val qy = (dy * sr - dx * si) / d + qay
            return floatArrayOf(-qy, qx * f)
        }

        val topY = toMesh(pins[TOP * 2], pins[TOP * 2 + 1])[0]
        val backZ = toMesh(pins[BACK * 2], pins[BACK * 2 + 1])[1]
        val earZ = toMesh(pins[EAR * 2], pins[EAR * 2 + 1])[1]
    }
}
