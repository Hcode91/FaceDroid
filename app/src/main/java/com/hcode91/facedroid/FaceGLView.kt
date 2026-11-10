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
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.Matrix as GLM

/** Simple textured 3D preview. Drag to turn the head, pinch to zoom. */
class FaceGLView(context: Context) : GLSurfaceView(context) {

    private val renderer = Renderer()
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.distance = (renderer.distance / d.scaleFactor).coerceIn(12f, 150f)
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

    fun setMesh(mesh: FaceMesh, texture: Bitmap) {
        queueEvent { renderer.setMesh(mesh, texture) }
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                renderer.yaw += (e.x - lastX) * 0.4f
                renderer.pitch = (renderer.pitch + (e.y - lastY) * 0.4f).coerceIn(-89f, 89f)
                lastX = e.x; lastY = e.y
                requestRender()
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

        private var mesh: FaceMesh? = null
        private var texture: Bitmap? = null
        private var dirty = false

        private var program = 0
        private var vbo = 0
        private var ibo = 0
        private var tex = 0
        private var indexCount = 0
        private val proj = FloatArray(16)
        private val viewM = FloatArray(16)
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private val tmp = FloatArray(16)

        fun setMesh(m: FaceMesh, t: Bitmap) { mesh = m; texture = t; dirty = true }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.12f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            program = link(VS, FS)
            val ids = IntArray(3)
            GLES20.glGenBuffers(2, ids, 0)
            GLES20.glGenTextures(1, ids, 2)
            vbo = ids[0]; ibo = ids[1]; tex = ids[2]
            dirty = mesh != null
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            GLM.perspectiveM(proj, 0, 30f, w.toFloat() / maxOf(1, h), 1f, 400f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (dirty) upload()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (indexCount == 0) return

            GLM.setLookAtM(viewM, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
            GLM.setIdentityM(model, 0)
            GLM.rotateM(model, 0, pitch, 1f, 0f, 0f)
            GLM.rotateM(model, 0, yaw, 0f, 1f, 0f)
            GLM.multiplyMM(tmp, 0, viewM, 0, model, 0)
            GLM.multiplyMM(mvp, 0, proj, 0, tmp, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            val stride = 8 * 4
            attrib("aPos", 3, stride, 0)
            attrib("aNormal", 3, stride, 3 * 4)
            attrib("aUV", 2, stride, 6 * 4)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        }

        private fun attrib(name: String, size: Int, stride: Int, offset: Int) {
            val loc = GLES20.glGetAttribLocation(program, name)
            if (loc < 0) return
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offset)
        }

        private fun upload() {
            val m = mesh ?: return
            val t = texture ?: return
            dirty = false
            val n = m.positions.size / 3
            val vb: FloatBuffer = ByteBuffer.allocateDirect(n * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until n) {
                vb.put(m.positions, i * 3, 3)
                vb.put(m.normals, i * 3, 3)
                vb.put(m.uvs, i * 2, 2)
            }
            vb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 8 * 4, vb, GLES20.GL_STATIC_DRAW)

            val ib: ShortBuffer = ByteBuffer.allocateDirect(m.tris.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in m.tris) ib.put(i.toShort())
            ib.position(0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.tris.size * 2, ib, GLES20.GL_STATIC_DRAW)
            indexCount = m.tris.size

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, t, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
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
