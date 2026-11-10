package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable

/** Finds the 468 face-mesh landmarks in a photo. Output is x, y, z per landmark, all in photo pixels. */
class LandmarkDetector(context: Context) : Closeable {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.3f)
            .setMinFacePresenceConfidence(0.3f)
            .build()
    )

    fun detect(bitmap: Bitmap): FloatArray? {
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val face = result.faceLandmarks().firstOrNull() ?: return null
        if (face.size < FaceTopology.N) return null
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val out = FloatArray(FaceTopology.N * 3)
        for (i in 0 until FaceTopology.N) {
            val l = face[i]
            out[i * 3] = l.x() * w
            out[i * 3 + 1] = l.y() * h
            out[i * 3 + 2] = l.z() * w // MediaPipe z uses the same scale as x; smaller = closer to camera
        }
        return out
    }

    override fun close() = landmarker.close()

    companion object {
        /** If no face is found, drop the template face in the middle of the photo so it can be pinned by hand. */
        fun templateLandmarks(topo: FaceTopology, width: Int, height: Int): FloatArray {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            for (i in 0 until FaceTopology.N) { minX = minOf(minX, topo.canon[i * 3]); maxX = maxOf(maxX, topo.canon[i * 3]) }
            val s = width * 0.5f / (maxX - minX)
            val out = FloatArray(FaceTopology.N * 3)
            for (i in 0 until FaceTopology.N) {
                out[i * 3] = width / 2f + topo.canon[i * 3] * s
                out[i * 3 + 1] = height / 2f - topo.canon[i * 3 + 1] * s
                out[i * 3 + 2] = -topo.canon[i * 3 + 2] * s
            }
            return out
        }
    }
}
