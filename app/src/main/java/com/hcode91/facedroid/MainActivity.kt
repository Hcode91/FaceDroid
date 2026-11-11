package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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

/** 3D head from a face photo, in four steps: photos, face pins, side pins, result. */
class MainActivity : ComponentActivity() {

    private companion object { const val PHOTOS = 0; const val PINS = 1; const val SIDE = 2; const val RESULT = 3 }

    private val worker = Executors.newSingleThreadExecutor()
    private var detector: LandmarkDetector? = null
    private lateinit var topo: FaceTopology

    private lateinit var steps: StepBar
    private lateinit var status: TextView
    private lateinit var photosPane: View
    private lateinit var frontTile: PhotoTile
    private lateinit var sideTile: PhotoTile
    private lateinit var pinView: PinEditorView
    private lateinit var sideView: PointPinView
    private lateinit var glView: FaceGLView
    private lateinit var uvView: ImageView
    private val optionRows = ArrayList<View>()
    private lateinit var depthRow: View
    private lateinit var meshButton: Button
    private lateinit var view3dButton: Button
    private lateinit var viewTexButton: Button
    private lateinit var headButton: Button
    private lateinit var viewerButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button

    private var step = PHOTOS
    private var showTexture = false
    private var photo: Bitmap? = null
    private var sidePhoto: Bitmap? = null
    private var depthScale = 1f
    private var fullHead = true
    private var builtMesh: FaceMesh? = null
    private var builtTexture: Bitmap? = null
    /** The head as built from the photos, and the editor state applied on top of it (if edited). */
    private var editBaseMesh: FaceMesh? = null
    private var editBase: List<MeshPart>? = null
    private var editState: EditState? = null
    private var lastExport: List<Uri> = emptyList()
    private var busy = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadPhoto(uri) }
    private val pickSide = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadSidePhoto(uri) }
    private val imageOnly get() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topo = FaceTopology.load(this)
        buildUi()
        setStep(PHOTOS)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(Ui.titleBar(this, "3D head"))
        steps = StepBar(this, listOf("Photos", "Face pins", "Side pins", "Result")) { setStep(it) }
        root.addView(steps)
        status = Ui.hint(this)
        root.addView(status)

        // ---- content
        val stage = FrameLayout(this)
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        frontTile = PhotoTile(this, "Front photo", "Needed – face looking straight at the camera") { pickPhoto.launch(imageOnly) }
        sideTile = PhotoTile(this, "Side photo", "Optional – gives the real shape and texture of the sides and back") { pickSide.launch(imageOnly) }
        photosPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(frontTile); addView(sideTile)
            addView(Ui.hint(context).apply { text = "Tips: even lighting, neutral expression, hair off the face. The side photo should be the same person with the same hair, turned a full 90°." })
        }
        pinView = PinEditorView(this).apply { topology = topo; onChanged = { builtMesh = null; builtTexture = null } }
        sideView = PointPinView(this).apply { onChanged = { builtMesh = null; builtTexture = null } }
        glView = FaceGLView(this)
        uvView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(40, 40, 44)) }
        stage.addView(photosPane, full); stage.addView(pinView, full); stage.addView(sideView, full); stage.addView(glView, full); stage.addView(uvView, full)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- options for each step (only the current step's row is shown)
        fun options(build: LinearLayout.() -> Unit): View = Ui.scroll(Ui.row(this).apply(build)).also { optionRows.add(it); root.addView(it) }
        options { }                                                   // photos: nothing extra
        options {
            meshButton = Ui.secondary(context, "Show mesh") { pinView.showMesh = !pinView.showMesh; Ui.setChecked(meshButton, pinView.showMesh) }
            Ui.setChecked(meshButton, true)
            addView(meshButton)
            addView(Ui.secondary(context, "Reset pins") { pinView.resetPins() })
        }
        options {
            addView(Ui.secondary(context, "Flip (face points the other way)") { sidePhoto?.let { sideView.setPoints(SideProfile.flip(sideView.points, it.width)) } })
            addView(Ui.secondary(context, "Reset pins") { sidePhoto?.let { sideView.setPoints(SideProfile.defaultPoints(it.width, it.height)) } })
        }
        options {
            addView(Ui.label(context, "Show"))
            view3dButton = Ui.secondary(context, "3D model") { showTexture = false; showResult() }
            viewTexButton = Ui.secondary(context, "Texture") { showTexture = true; showResult() }
            addView(view3dButton); addView(viewTexButton)
            addView(Ui.label(context, "  Shape"))
            headButton = Ui.secondary(context, "") {
                fullHead = !fullHead
                builtMesh = null; builtTexture = null
                showResult()
            }
            addView(headButton)
            addView(Ui.label(context, "  Then"))
            addView(Ui.secondary(context, "Edit shape") { openEditor() })
            addView(Ui.secondary(context, "Put on a body ›") { ensureBuilt { _, _ -> startActivity(Intent(this@MainActivity, BodyActivity::class.java)) } })
            viewerButton = Ui.secondary(context, "Open saved file") {
                startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putParcelableArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(lastExport)))
            }
            viewerButton.visibility = View.GONE
            addView(viewerButton)
        }
        depthRow = Ui.row(this).apply {
            addView(Ui.label(context, "Depth (flatter ← → deeper)"))
            addView(SeekBar(context).apply {
                max = 200; progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        depthScale = (s?.progress ?: 100) / 100f
                        builtMesh = null
                        if (step == RESULT) showResult()
                    }
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(depthRow)

        // ---- bottom bar: back one step, and the one main action
        val bottom = Ui.row(this).apply { setPadding(Ui.dp(context, 8f), Ui.dp(context, 2f), Ui.dp(context, 8f), Ui.dp(context, 8f)) }
        backButton = Ui.secondary(this, "‹ Back") { setStep(if (step == RESULT && sidePhoto == null) PINS else step - 1) }
        nextButton = Ui.primary(this, "Next") { next() }
        bottom.addView(backButton)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(nextButton)
        root.addView(bottom)
        setContentView(root)
    }

    private fun next() {
        when (step) {
            PHOTOS -> setStep(PINS)
            PINS -> setStep(if (sidePhoto != null) SIDE else RESULT)
            SIDE -> setStep(RESULT)
            RESULT -> export()
        }
    }

    private fun setStep(target: Int) {
        var s = target
        if (s > PHOTOS && photo == null) { s = PHOTOS; status.text = "Choose a front photo first." }
        else if (s == SIDE && sidePhoto == null) { s = PHOTOS; status.text = "Add a side photo first – it's optional, so you can also skip straight to Result." }
        else status.text = when (s) {
            PHOTOS -> "Choose a front photo. A side photo is optional."
            PINS -> "Check the yellow pins sit on the eyes, nose, mouth and jaw. Drag any that are off – they turn red. Pinch to zoom."
            SIDE -> "Drag the 5 pins onto the nose tip, chin, top of head, back of head and ear."
            else -> "Drag to turn, pinch to zoom."
        }
        step = s
        steps.select(s)
        steps.setUsable(PINS, photo != null); steps.setUsable(SIDE, sidePhoto != null); steps.setUsable(RESULT, photo != null)
        photosPane.visibility = if (s == PHOTOS) View.VISIBLE else View.GONE
        pinView.visibility = if (s == PINS) View.VISIBLE else View.GONE
        sideView.visibility = if (s == SIDE) View.VISIBLE else View.GONE
        for ((i, row) in optionRows.withIndex()) row.visibility = if (i == s) View.VISIBLE else View.GONE
        backButton.visibility = if (s == PHOTOS) View.INVISIBLE else View.VISIBLE
        nextButton.text = when (s) {
            PHOTOS -> "Next: check pins"
            PINS -> if (sidePhoto != null) "Next: side pins" else "Next: see result"
            SIDE -> "Next: see result"
            else -> "Save OBJ"
        }
        Ui.setUsable(nextButton, photo != null)
        if (s == RESULT) showResult() else { glView.visibility = View.GONE; uvView.visibility = View.GONE; depthRow.visibility = View.GONE }
    }

    private fun showResult() {
        glView.visibility = if (showTexture) View.GONE else View.VISIBLE
        uvView.visibility = if (showTexture) View.VISIBLE else View.GONE
        depthRow.visibility = if (showTexture) View.GONE else View.VISIBLE
        Ui.setChecked(view3dButton, !showTexture); Ui.setChecked(viewTexButton, showTexture)
        headButton.text = if (fullHead) "Full head" else "Face only"
        ensureBuilt { mesh, tex ->
            if (step != RESULT) return@ensureBuilt
            if (showTexture) uvView.setImageBitmap(tex) else glView.setModel(RenderModel.fromParts(listOf(MeshPart.from(mesh, tex))))
        }
    }

    private fun loadPhoto(uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Finding the face…"
        worker.execute {
            try {
                val bmp = Photos.decode(this, uri)
                val det = detector ?: LandmarkDetector(this).also { detector = it }
                val found = det.detect(bmp)
                val lm = found ?: LandmarkDetector.templateLandmarks(topo, bmp.width, bmp.height)
                runOnUiThread {
                    photo = bmp
                    builtMesh = null; builtTexture = null
                    pinView.setFace(bmp, lm)
                    frontTile.set(bmp, if (found != null) "Chosen ✓ – face found" else "Chosen – no face found, place the pins by hand")
                    busy = false
                    setStep(PHOTOS)
                    status.text = if (found != null) "Face found. Add a side photo if you have one, then tap Next."
                        else "No face was detected, so a template was placed. You'll need to drag the pins onto the features in the next step."
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load that photo: ${e.message}"; busy = false }
            }
        }
    }

    private fun loadSidePhoto(uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Loading the side photo…"
        worker.execute {
            try {
                val bmp = Photos.decode(this, uri)
                runOnUiThread {
                    sidePhoto = bmp
                    sideView.set(bmp, SideProfile.PINS, SideProfile.defaultPoints(bmp.width, bmp.height), SideProfile.LINES)
                    sideTile.set(bmp, "Chosen ✓ – you'll place 5 pins on it")
                    builtMesh = null; builtTexture = null
                    busy = false
                    setStep(PHOTOS)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load that photo: ${e.message}"; busy = false }
            }
        }
    }

    /** Builds the mesh and bakes the texture if anything changed, then calls [done] on the UI thread. */
    private fun ensureBuilt(done: (FaceMesh, Bitmap) -> Unit) {
        val p = photo
        val lm = pinView.current
        if (p == null || lm == null) { status.text = "Choose a front photo first."; return }
        val m = builtMesh; val t = builtTexture
        if (m != null && t != null) { done(m, t); return }
        if (busy) return
        busy = true
        status.text = "Building the model…"
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
                    editBaseMesh = null; editBase = null; editState = null
                    busy = false
                    status.text = "Drag to turn, pinch to zoom. Happy with it? Tap Save OBJ."
                    done(mesh, tex)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't build the model: ${e.message}"; busy = false }
            }
        }
    }

    private fun openEditor() {
        ensureBuilt { mesh, tex ->
            val baseMesh = editBaseMesh ?: mesh
            val base = editBase ?: listOf(MeshPart.from(baseMesh, tex))
            editBaseMesh = baseMesh; editBase = base
            EditStore.parts = base; EditStore.roles = null; EditStore.state = editState
            startActivity(Intent(this, EditorActivity::class.java))
        }
    }

    private fun export() {
        ensureBuilt { mesh, tex ->
            busy = true
            status.text = "Saving…"
            val name = "face_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            worker.execute {
                val ok = try { lastExport = ObjExporter.export(this, listOf(MeshPart.from(mesh, tex)), name); true } catch (e: Throwable) { false }
                val msg = if (ok) "Saved $name.obj with its texture to Download/${ObjExporter.FOLDER}" else "Couldn't save the file."
                runOnUiThread {
                    busy = false
                    status.text = msg
                    if (ok) viewerButton.visibility = View.VISIBLE
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val r = EditStore.result
        val bm = editBaseMesh
        if (r != null && bm != null && r.base === editBase) {
            EditStore.result = null
            val e = r.edited[0]
            val edited = FaceMesh(e.positions, e.normals, bm.uvs, bm.tris, bm.srcPx, bm.triView, bm.sidePx, bm.texWidth, bm.texHeight, bm.fullHead, bm.hairSampleX, bm.hairSampleY)
            builtMesh = edited; editState = r.state
            if (edited.fullHead) HeadStore.mesh = edited
            if (step == RESULT) showResult()
            status.text = "Showing your edited shape. Save OBJ and the body screen both use it. Changing pins, photos or depth rebuilds from the photos and drops the edits."
        }
    }

    override fun onPause() { glView.onPause(); super.onPause() }

    override fun onDestroy() {
        worker.execute { detector?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
