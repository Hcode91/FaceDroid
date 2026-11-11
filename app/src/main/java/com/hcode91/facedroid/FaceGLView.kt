package com.hcode91.facedroid

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import android.opengl.Matrix as GLM

/**
 * Textured 3D view. Normally: drag to turn, pinch to zoom. In [brushMode]: one finger reports
 * brush strokes through [onBrush], two fingers turn and zoom.
 */
class FaceGLView(context: Context) : GLSurfaceView(context) {

    private val renderer = Renderer()
    private var lastX = 0f
    private var lastY = 0f
    private var stroking = false

    var brushMode = false
    /** (phase, x, y) with phase 0 = down, 1 = move, 2 = up. */
    var onBrush: ((Int, Float, Float) -> Unit)? = null

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.distance = (renderer.distance / d.scaleFactor).coerceIn(8f, 150f)
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setModel(model: RenderModel) {
        queueEvent { renderer.setModel(model) }
        requestRender()
    }

    /** Replace the vertex data of the current model (same part count and sizes), keeping its textures. */
    fun updateGeometry(data: List<FloatArray>) {
        queueEvent { renderer.setGeometry(data) }
        requestRender()
    }

    fun resetView() {
        renderer.yaw = 0f; renderer.pitch = 0f; renderer.distance = 45f
        requestRender()
    }

    /** Ray under a screen point, in the model's display space: origin (0..2) and unit direction (3..5). */
    fun ray(x: Float, y: Float): FloatArray? {
        if (width == 0 || height == 0) return null
        val proj = FloatArray(16); val view = FloatArray(16); val model = FloatArray(16)
        val tmp = FloatArray(16); val mvp = FloatArray(16); val inv = FloatArray(16)
        GLM.perspectiveM(proj, 0, 30f, width.toFloat() / height, 1f, 400f)
        GLM.setLookAtM(view, 0, 0f, 0f, renderer.distance, 0f, 0f, 0f, 0f, 1f, 0f)
        GLM.setIdentityM(model, 0)
        GLM.rotateM(model, 0, renderer.pitch, 1f, 0f, 0f)
        GLM.rotateM(model, 0, renderer.yaw, 0f, 1f, 0f)
        GLM.multiplyMM(tmp, 0, view, 0, model, 0)
        GLM.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        if (!GLM.invertM(inv, 0, mvp, 0)) return null
        val nx = 2f * x / width - 1f; val ny = 1f - 2f * y / height
        val near = FloatArray(4); val far = FloatArray(4)
        GLM.multiplyMV(near, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        GLM.multiplyMV(far, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        for (i in 0 until 3) { near[i] /= near[3]; far[i] /= far[3] }
        val dx = far[0] - near[0]; val dy = far[1] - near[1]; val dz = far[2] - near[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-9f) return null
        return floatArrayOf(near[0], near[1], near[2], dx / l, dy / l, dz / l)
    }

    private fun focus(e: MotionEvent, skip: Int) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) { if (i == skip) continue; sx += e.getX(i); sy += e.getY(i); n++ }
        if (n > 0) { lastX = sx / n; lastY = sy / n }
    }

    private fun turn(dx: Float, dy: Float) {
        renderer.yaw += dx * 0.4f
        renderer.pitch = (renderer.pitch + dy * 0.4f).coerceIn(-89f, 89f)
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (brushMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { stroking = true; onBrush?.invoke(0, e.x, e.y) }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (stroking) { stroking = false; onBrush?.invoke(2, e.x, e.y) }
                    focus(e, -1)
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) {
                    if (stroking) onBrush?.invoke(1, e.x, e.y)
                } else {
                    val ox = lastX; val oy = lastY
                    focus(e, -1)
                    turn(lastX - ox, lastY - oy)
                }
                MotionEvent.ACTION_POINTER_UP -> focus(e, e.actionIndex)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stroking) { stroking = false; onBrush?.invoke(2, e.x, e.y) }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                turn(dx, dy)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
        }
        return true
    }

    private class Renderer : GLSurfaceView.Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        @Volatile var distance = 45f

        private var model: RenderModel? = null
        private var geometry: List<FloatArray>? = null
        private var dirty = false
        private var geoDirty = false

        private var program = 0
        private var vbos = IntArray(0)
        private var texs = IntArray(0)
        private var counts = IntArray(0)
        private var buffers = arrayOfNulls<FloatBuffer>(0)
        private val proj = FloatArray(16)
        private val viewM = FloatArray(16)
        private val modelM = FloatArray(16)
        private val mvp = FloatArray(16)
        private val tmp = FloatArray(16)

        fun setModel(m: RenderModel) { model = m; geometry = null; geoDirty = false; dirty = true }
        fun setGeometry(g: List<FloatArray>) { geometry = g; geoDirty = true }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.12f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            program = link(VS, FS)
            vbos = IntArray(0); texs = IntArray(0); counts = IntArray(0)
            dirty = model != null
            geoDirty = geometry != null
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            GLM.perspectiveM(proj, 0, 30f, w.toFloat() / maxOf(1, h), 1f, 400f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (dirty) upload()
            if (geoDirty) uploadGeometry()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (counts.isEmpty()) return

            GLM.setLookAtM(viewM, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
            GLM.setIdentityM(modelM, 0)
            GLM.rotateM(modelM, 0, pitch, 1f, 0f, 0f)
            GLM.rotateM(modelM, 0, yaw, 0f, 1f, 0f)
            GLM.multiplyMM(tmp, 0, viewM, 0, modelM, 0)
            GLM.multiplyMM(mvp, 0, proj, 0, tmp, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, modelM, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            val stride = 8 * 4
            for (p in counts.indices) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[p])
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbos[p])
                attrib("aPos", 3, stride, 0)
                attrib("aNormal", 3, stride, 3 * 4)
                attrib("aUV", 2, stride, 6 * 4)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, counts[p])
            }
        }

        private fun attrib(name: String, size: Int, stride: Int, offset: Int) {
            val loc = GLES20.glGetAttribLocation(program, name)
            if (loc < 0) return
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offset)
        }

        private fun fill(i: Int, data: FloatArray) {
            var fb = buffers[i]
            if (fb == null || fb.capacity() != data.size) {
                fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buffers[i] = fb
            }
            fb!!.clear(); fb.put(data).position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbos[i])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_DYNAMIC_DRAW)
            counts[i] = data.size / 8
        }

        private fun uploadGeometry() {
            geoDirty = false
            val g = geometry ?: return
            if (g.size != vbos.size) return
            for ((i, d) in g.withIndex()) fill(i, d)
        }

        private fun upload() {
            val m = model ?: return
            dirty = false
            if (vbos.isNotEmpty()) GLES20.glDeleteBuffers(vbos.size, vbos, 0)
            if (texs.isNotEmpty()) GLES20.glDeleteTextures(texs.size, texs, 0)
            val n = m.parts.size
            vbos = IntArray(n).also { GLES20.glGenBuffers(n, it, 0) }
            texs = IntArray(n).also { GLES20.glGenTextures(n, it, 0) }
            counts = IntArray(n)
            buffers = arrayOfNulls(n)
            val maxSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            for ((i, part) in m.parts.withIndex()) {
                fill(i, part.data)
                var bmp = part.texture ?: Bitmap.createBitmap(intArrayOf(0xFFD8D8D8.toInt()), 1, 1, Bitmap.Config.ARGB_8888)
                if (maxSize > 0 && (bmp.width > maxSize || bmp.height > maxSize)) {
                    val s = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
                    bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
                }
                val pot = bmp.width and (bmp.width - 1) == 0 && bmp.height and (bmp.height - 1) == 0
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[i])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (pot) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, if (pot) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, if (pot) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                if (pot) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        companion object {
            const val VS = """
                uniform mat4 uMVP;
                uniform mat4 uModel;
                attribute vec3 aPos;
                attribute vec3 aNormal;
                attribute vec2 aUV;
                varying vec2 vUV;
                varying vec3 vN;
                void main() {
                    vUV = vec2(aUV.x, 1.0 - aUV.y);
                    vN = (uModel * vec4(aNormal, 0.0)).xyz;
                    gl_Position = uMVP * vec4(aPos, 1.0);
                }
            """
            const val FS = """
                precision mediump float;
                uniform sampler2D uTex;
                varying vec2 vUV;
                varying vec3 vN;
                void main() {
                    vec3 n = normalize(vN);
                    if (!gl_FrontFacing) n = -n;
                    float d = max(dot(n, normalize(vec3(0.3, 0.4, 1.0))), 0.0);
                    vec3 c = texture2D(uTex, vUV).rgb;
                    gl_FragColor = vec4(c * (0.45 + 0.65 * d), 1.0);
                }
            """
        }
    }
}
