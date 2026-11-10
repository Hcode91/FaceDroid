package com.hcode91.facedroid

import android.graphics.Color

/** Which body points get pins in each photo, their labels, and skeleton lines. Ids are MediaPipe pose numbers. */
object BodyPins {
    enum class View { FRONT, SIDE, BACK }

    fun ids(v: View): IntArray = when (v) {
        View.FRONT -> intArrayOf(BodyTemplate.TOP, 0, 2, 5, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 31, 32)
        // Side photos use the person's left-side numbers; both sides look the same from the side.
        View.SIDE -> intArrayOf(BodyTemplate.TOP, 0, 7, 11, 13, 15, 23, 25, 27, 29, 31)
        View.BACK -> intArrayOf(BodyTemplate.TOP, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 29, 30)
    }

    private val NAMES = mapOf(
        BodyTemplate.TOP to "Top of head", 0 to "Nose", 2 to "Left eye", 5 to "Right eye", 7 to "Ear",
        11 to "Left shoulder", 12 to "Right shoulder", 13 to "Left elbow", 14 to "Right elbow",
        15 to "Left wrist", 16 to "Right wrist", 23 to "Left hip", 24 to "Right hip",
        25 to "Left knee", 26 to "Right knee", 27 to "Left ankle", 28 to "Right ankle",
        29 to "Left heel", 30 to "Right heel", 31 to "Left toes", 32 to "Right toes",
    )

    private val BONES = intArrayOf(
        11, 12, 11, 13, 13, 15, 12, 14, 14, 16, 11, 23, 12, 24, 23, 24, 23, 25, 25, 27, 24, 26, 26, 28,
        27, 29, 29, 31, 27, 31, 28, 30, 30, 32, 28, 32, 0, BodyTemplate.TOP, 2, 5, 0, 7, 7, 11,
    )

    fun pins(v: View): List<PointPinView.Pin> = ids(v).map { id ->
        val name = NAMES[id] ?: "Point $id"
        val label = if (v == View.SIDE) name.removePrefix("Left ").replaceFirstChar { it.uppercase() } else name
        val colour = when {
            v == View.SIDE || id == 0 || id == BodyTemplate.TOP -> Color.rgb(255, 200, 0)
            id % 2 == 1 || id == 2 -> Color.rgb(80, 220, 255)   // person's left
            else -> Color.rgb(255, 110, 200)                     // person's right
        }
        PointPinView.Pin(label, colour)
    }

    fun lines(v: View): IntArray {
        val ids = ids(v)
        val out = ArrayList<Int>()
        for (i in 0 until BONES.size / 2) {
            val a = ids.indexOf(BONES[i * 2]); val b = ids.indexOf(BONES[i * 2 + 1])
            if (a >= 0 && b >= 0) { out.add(a); out.add(b) }
        }
        return out.toIntArray()
    }

    /** Template skeleton dropped into the middle of the photo, for when detection fails. */
    fun defaultPoints(v: View, t: BodyTemplate, w: Int, h: Int): FloatArray {
        val ids = ids(v)
        val k = h * 0.85f / t.height
        val cx = w / 2f
        val cy = h / 2f + (t.maxY + t.minY) / 2f * k
        return FloatArray(ids.size * 2) { i ->
            val p = t.kp(ids[i / 2])
            if (i % 2 == 0) when (v) {
                View.FRONT -> cx + p[0] * k
                View.BACK -> cx - p[0] * k
                View.SIDE -> cx + p[2] * k
            } else cy - p[1] * k
        }
    }

    /** Put detected landmarks (33 points, x/y px) into this view's pin order. */
    fun fromDetection(v: View, detected: FloatArray, visibility: FloatArray, t: BodyTemplate, w: Int, h: Int, mask: Mask?): FloatArray {
        val pts = detected.copyOf()
        val pairs = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 2, 5, 7, 8)
        fun swapAll() { for (i in 0 until pairs.size / 2) for (c in 0..1) { val a = pairs[i * 2] * 2 + c; val b = pairs[i * 2 + 1] * 2 + c; val tmp = pts[a]; pts[a] = pts[b]; pts[b] = tmp } }
        when (v) {
            // Front: the person's left should be on the image's right. Back: on the image's left.
            View.FRONT, View.BACK -> {
                var wrong = 0
                for (i in 0 until pairs.size / 2) {
                    val leftRight = pts[pairs[i * 2] * 2] > pts[pairs[i * 2 + 1] * 2]
                    if (leftRight != (v == View.FRONT)) wrong++
                }
                if (wrong > pairs.size / 4) swapAll()
            }
            // Side: use whichever side the camera sees best.
            View.SIDE -> {
                var l = 0f; var r = 0f
                for (i in 0 until pairs.size / 2) { l += visibility[pairs[i * 2]]; r += visibility[pairs[i * 2 + 1]] }
                if (r > l) swapAll()
            }
        }
        val ids = ids(v)
        val out = FloatArray(ids.size * 2)
        for ((i, id) in ids.withIndex()) {
            if (id == BodyTemplate.TOP) {
                // Top of head: highest point of the person outline above the nose, else a guess from nose/eyes.
                // Search straight up from the nose (front/back) or from between nose and ear (side).
                val nx = when (v) {
                    View.SIDE -> (pts[0] + pts[7 * 2]) / 2f
                    View.BACK -> (pts[11 * 2] + pts[12 * 2]) / 2f
                    View.FRONT -> pts[0]
                }
                val ny = if (v == View.BACK) (pts[11 * 2 + 1] + pts[12 * 2 + 1]) / 2f else pts[1]
                val earDist = kotlin.math.hypot(pts[7 * 2] - pts[8 * 2], pts[7 * 2 + 1] - pts[8 * 2 + 1])
                var top = ny - maxOf(earDist, h * 0.05f) * 1.1f
                if (mask != null) {
                    val mx = (nx * mask.width / w).toInt().coerceIn(0, mask.width - 1)
                    var y = (ny * mask.height / h).toInt().coerceIn(0, mask.height - 1)
                    if (mask.inside(mx, y)) {
                        while (y > 0 && mask.inside(mx, y - 1)) y--
                        top = y * h.toFloat() / mask.height
                    }
                }
                out[i * 2] = nx; out[i * 2 + 1] = top
            } else {
                out[i * 2] = pts[id * 2]; out[i * 2 + 1] = pts[id * 2 + 1]
            }
        }
        return out
    }
}
