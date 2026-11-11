package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private enum class Mode { PINS, SIDE, VIEW3D, UV }

    private val worker = Executors.newSingleThreadExecutor()
    private var detector: LandmarkDetector? = null
    private lateinit var topo: FaceTopology

    private lateinit var pinView: PinEditorView
    private lateinit var glView: FaceGLView
    private lateinit var uvView: ImageView
    private lateinit var status: TextView
    private lateinit var depthRow: LinearLayout
    private lateinit var meshButton: Button
    private lateinit var flipButton: Button
    private lateinit var sideView: PointPinView
    private var sidePhoto: Bitmap? = null
    private val modeButtons = HashMap<Mode, Button>()

    private var photo: Bitmap? = null
    private var mode = Mode.PINS
    private var depthScale = 1f
    private var fullHead = true
    private var lastExport: List<Uri> = emptyList()
    private var builtMesh: FaceMesh? = null
    private var builtTexture: Bitmap? = null
    private var busy = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhoto(uri)
    }
    private val pickSide = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadSidePhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topo = FaceTopology.load(this)
        buildUi()
        setMode(Mode.PINS)
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 22)) }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            bar.addView(this)
        }
        button("Photo") { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        button("Side photo") { pickSide.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        modeButtons[Mode.PINS] = button("Pins") { setMode(Mode.PINS) }
        modeButtons[Mode.SIDE] = button("Side pins") { setMode(Mode.SIDE) }
        modeButtons[Mode.VIEW3D] = button("3D") { setMode(Mode.VIEW3D) }
        modeButtons[Mode.UV] = button("UV map") { setMode(Mode.UV) }
        meshButton = button("Mesh: on") {
            pinView.showMesh = !pinView.showMesh
            meshButton.text = if (pinView.showMesh) "Mesh: on" else "Mesh: off"
        }
        flipButton = button("Flip side") {
            val p = sidePhoto ?: return@button
            sideView.setPoints(SideProfile.flip(sideView.points, p.width))
        }
        button("Reset pins") {
            if (mode == Mode.SIDE) sidePhoto?.let { sideView.setPoints(SideProfile.defaultPoints(it.width, it.height)) }
            else pinView.resetPins()
        }
        button("Head: full") {}.apply {
            setOnClickListener {
                fullHead = !fullHead
                text = if (fullHead) "Head: full" else "Head: face only"
                builtMesh = null; builtTexture = null
                if (mode != Mode.PINS) refreshPreview()
            }
        }
        button("Export") { export() }
        button("Body / Measure") { startActivity(Intent(this, BodyActivity::class.java)) }
        button("OBJ viewer") {
            startActivity(Intent(this, ViewerActivity::class.java).apply {
                if (lastExport.isNotEmpty()) putParcelableArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(lastExport))
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            text = "Pick a clear, front-on photo of a face."
        }
        root.addView(status)

        depthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            addView(TextView(this@MainActivity).apply { text = "Depth"; setTextColor(Color.LTGRAY) })
            addView(SeekBar(this@MainActivity).apply {
                max = 200
                progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        depthScale = (s?.progress ?: 100) / 100f
                        builtMesh = null
                        if (mode == Mode.VIEW3D) refreshPreview()
                    }
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(depthRow)

        val stage = FrameLayout(this)
        pinView = PinEditorView(this).apply {
            topology = topo
            onChanged = { builtMesh = null; builtTexture = null }
        }
        sideView = PointPinView(this).apply {
            hint = "Tap “Side photo” to add a side-on photo (optional)"
            onChanged = { builtMesh = null; builtTexture = null }
        }
        glView = FaceGLView(this)
        uvView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(40, 40, 44)) }
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        stage.addView(pinView, full)
        stage.addView(sideView, full)
        stage.addView(glView, full)
        stage.addView(uvView, full)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun setMode(m: Mode) {
        mode = m
        pinView.visibility = if (m == Mode.PINS) View.VISIBLE else View.GONE
        sideView.visibility = if (m == Mode.SIDE) View.VISIBLE else View.GONE
        flipButton.visibility = if (m == Mode.SIDE) View.VISIBLE else View.GONE
        glView.visibility = if (m == Mode.VIEW3D) View.VISIBLE else View.GONE
        uvView.visibility = if (m == Mode.UV) View.VISIBLE else View.GONE
        depthRow.visibility = if (m == Mode.VIEW3D) View.VISIBLE else View.GONE
        meshButton.visibility = if (m == Mode.PINS) View.VISIBLE else View.GONE
        for ((k, b) in modeButtons) b.alpha = if (k == m) 1f else 0.6f
        if (m == Mode.VIEW3D || m == Mode.UV) refreshPreview()
    }

    private fun loadPhoto(uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Finding face…"
        worker.execute {
            try {
                val bmp = decode(uri)
                val det = detector ?: LandmarkDetector(this).also { detector = it }
                val found = det.detect(bmp)
                val lm = found ?: LandmarkDetector.templateLandmarks(topo, bmp.width, bmp.height)
                runOnUiThread {
                    photo = bmp
                    builtMesh = null
                    builtTexture = null
                    pinView.setFace(bmp, lm)
                    status.text = if (found != null)
                        "Face found. Drag the yellow pins onto the eyes, nostrils, mouth and jaw if they're off. Pinch to zoom."
                    else
                        "No face detected – a template face was placed instead. Drag the pins onto the features."
                    busy = false
                    setMode(Mode.PINS)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load photo: ${e.message}"; busy = false }
            }
        }
    }

    private fun decode(uri: Uri): Bitmap = Photos.decode(this, uri)

    private fun loadSidePhoto(uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Loading side photo…"
        worker.execute {
            try {
                val bmp = decode(uri)
                runOnUiThread {
                    sidePhoto = bmp
                    sideView.set(bmp, SideProfile.PINS, SideProfile.defaultPoints(bmp.width, bmp.height), SideProfile.LINES)
                    builtMesh = null; builtTexture = null
                    busy = false
                    status.text = "Drag the pins onto the nose tip, chin, top and back of the head, and the ear. Use “Flip side” if the face points the other way."
                    setMode(Mode.SIDE)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load side photo: ${e.message}"; busy = false }
            }
        }
    }

    /** Builds the mesh and bakes the UV texture if anything changed, then calls [done] on the UI thread. */
    private fun ensureBuilt(done: (FaceMesh, Bitmap) -> Unit) {
        val p = photo
        val lm = pinView.current
        if (p == null || lm == null) { status.text = "Pick a photo first."; return }
        val m = builtMesh; val t = builtTexture
        if (m != null && t != null) { done(m, t); return }
        if (busy) return
        busy = true
        status.text = "Building mesh and UV map…"
        val lmCopy = lm.copyOf()
        val depth = depthScale
        val head = fullHead
        val oldTexture = t
        val sp = sidePhoto
        val side = if (head && sp != null && sideView.hasPhoto) FaceMesh.SideInput(sideView.points, sp.width, sp.height) else null
        worker.execute {
            try {
                val mesh = FaceMesh.build(topo, lmCopy, depth, head, side)
                // Depth doesn't change the texture (unless a side photo maps onto the reshaped skull).
                val tex = if (oldTexture != null && oldTexture.width == mesh.texWidth && side == null) oldTexture
                    else TextureBaker.bake(p, mesh, if (side != null) sp else null)
                if (head) { HeadStore.mesh = mesh; HeadStore.texture = tex }
                runOnUiThread {
                    builtMesh = mesh
                    builtTexture = tex
                    busy = false
                    status.text = "${mesh.triCount} triangles, ${mesh.vertexCount} vertices, ${tex.width}×${tex.height} texture."
                    done(mesh, tex)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Build failed: ${e.message}"; busy = false }
            }
        }
    }

    private fun refreshPreview() {
        ensureBuilt { mesh, tex ->
            when (mode) {
                Mode.VIEW3D -> glView.setModel(RenderModel.fromParts(listOf(MeshPart.from(mesh, tex))))
                Mode.UV -> uvView.setImageBitmap(tex)
                else -> {}
            }
        }
    }

    private fun export() {
        ensureBuilt { mesh, tex ->
            busy = true
            status.text = "Saving…"
            val name = "face_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            worker.execute {
                val msg = try {
                    lastExport = ObjExporter.export(this, listOf(MeshPart.from(mesh, tex)), name)
                    "Saved $name.obj, .mtl and texture to Download/${ObjExporter.FOLDER}"
                } catch (e: Throwable) {
                    "Export failed: ${e.message}"
                }
                runOnUiThread {
                    busy = false
                    status.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { glView.onPause(); super.onPause() }

    override fun onDestroy() {
        worker.execute { detector?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
