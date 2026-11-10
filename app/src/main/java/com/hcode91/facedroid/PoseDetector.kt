package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable
import java.nio.ByteOrder

/** Finds the 33 MediaPipe body points and a person/background mask in a full-length photo. */
class PoseDetector(context: Context) : Closeable {

    class Result(val points: FloatArray, val visibility: FloatArray, val mask: Mask?)

    private val landmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.3f)
            .setMinPosePresenceConfidence(0.3f)
            .setOutputSegmentationMasks(true)
            .build()
    )

    fun detect(bitmap: Bitmap): Result? {
        val res = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val lms = res.landmarks().firstOrNull() ?: return null
        val pts = FloatArray(33 * 2)
        val vis = FloatArray(33)
        for (i in 0 until minOf(33, lms.size)) {
            pts[i * 2] = lms[i].x() * bitmap.width
            pts[i * 2 + 1] = lms[i].y() * bitmap.height
            vis[i] = lms[i].visibility().orElse(0f)
        }
        val mask = try {
            res.segmentationMasks().orElse(null)?.firstOrNull()?.let { img ->
                val fb = ByteBufferExtractor.extract(img).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val w = img.width; val h = img.height
                if (fb.remaining() < w * h) null
                else Mask(w, h, ByteArray(w * h) { if (fb.get(it) > 0.5f) 1 else 0 })
            }
        } catch (e: Exception) { null }
        return Result(pts, vis, mask)
    }

    override fun close() = landmarker.close()

    companion object {
        /** Semi-transparent tint of the person mask, for drawing over the photo. */
        fun maskOverlay(mask: Mask): Bitmap {
            val px = IntArray(mask.width * mask.height) { if (mask.data[it].toInt() != 0) 0xFF00C8FF.toInt() else 0 }
            return Bitmap.createBitmap(px, mask.width, mask.height, Bitmap.Config.ARGB_8888)
        }
    }
}
