package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Full body from a front photo (required) plus optional side and back photos, standing in an
 * A-pose (arms slightly away from the body). Uses the FaceDroid head from the face screen if built.
 */
class BodyActivity : ComponentActivity() {

    private enum class Mode { FRONT, SIDE, BACK, VIEW3D, UV }

    private val worker = Executors.newSingleThreadExecutor()
    private var detector: PoseDetector? = null
    @Volatile private var template: BodyTemplate? = null

    private val views = BodyPins.View.values()
    private val photos = arrayOfNulls<Bitmap>(3)
    private val masks = arrayOfNulls<Mask>(3)
    private val detectedPins = arrayOfNulls<FloatArray>(3)
    private lateinit var pinViews: Array<PointPinView>
    private lateinit var glView: FaceGLView
    private lateinit var uvView: ImageView
    private lateinit var status: TextView
    private lateinit var heightField: EditText
    private lateinit var headButton: Button
    private lateinit var outlineButton: Button
    private val modeButtons = HashMap<Mode, Button>()

    private var mode = Mode.FRONT
    private var useHead = true
    private var useOutline = true
    private var built: List<MeshPart>? = null
    private var lastExport: List<Uri> = emptyList()
    private var busy = false

    private val pickers = views.map { v ->
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadPhoto(v, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setMode(Mode.FRONT)
        worker.execute { template = assets.open("body_template.txt").use { BodyTemplate.parse(it) } }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 22)) }
        fun bar(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun LinearLayout.button(label: String, onClick: () -> Unit) = Button(this@BodyActivity).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
            this@button.addView(this)
        }
        val row1 = bar()
        row1.button("Front photo") { pick(BodyPins.View.FRONT) }
        row1.button("Side photo") { pick(BodyPins.View.SIDE) }
        row1.button("Back photo") { pick(BodyPins.View.BACK) }
        modeButtons[Mode.FRONT] = row1.button("Front") { setMode(Mode.FRONT) }
        modeButtons[Mode.SIDE] = row1.button("Side") { setMode(Mode.SIDE) }
        modeButtons[Mode.BACK] = row1.button("Back") { setMode(Mode.BACK) }
        modeButtons[Mode.VIEW3D] = row1.button("3D") { setMode(Mode.VIEW3D) }
        modeButtons[Mode.UV] = row1.button("UV map") { setMode(Mode.UV) }
        root.addView(HorizontalScrollView(this).apply { addView(row1) })

        val row2 = bar()
        row2.addView(TextView(this).apply { text = "  Height"; setTextColor(Color.LTGRAY) })
        heightField = EditText(this).apply {
            setText("175"); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); minEms = 3
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { built = null }
            })
        }
        row2.addView(heightField)
        row2.addView(TextView(this).apply { text = "cm  "; setTextColor(Color.LTGRAY) })
        headButton = row2.button("") { useHead = !useHead; updateToggles(); built = null }
        outlineButton = row2.button("") {
            useOutline = !useOutline; updateToggles(); built = null
            pinViews.forEach { it.showOverlay = useOutline }
        }
        row2.button("Reset pins") { resetPins() }
        row2.button("Export") { export() }
        row2.button("OBJ viewer") {
            startActivity(Intent(this, ViewerActivity::class.java).apply {
                if (lastExport.isNotEmpty()) putParcelableArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(lastExport))
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(row2) })

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            text = "Full-length photos, standing straight, arms slightly away from the body, fitted clothes. " +
                "Front is required; side and back are optional. Build a head on the face screen first to use it here."
        }
        root.addView(status)

        val stage = FrameLayout(this)
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        pinViews = Array(3) { i ->
            PointPinView(this).apply {
                hint = "Tap “${views[i].name.lowercase().replaceFirstChar { it.uppercase() }} photo” to add one"
                onChanged = { built = null }
                stage.addView(this, full)
            }
        }
        glView = FaceGLView(this).also { stage.addView(it, full) }
        uvView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(40, 40, 44)) }
        stage.addView(uvView, full)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateToggles()
    }

    private fun updateToggles() {
        headButton.text = if (useHead) "Head: FaceDroid" else "Head: template"
        outlineButton.text = if (useOutline) "Outline fit: on" else "Outline fit: off"
    }

    private fun pick(v: BodyPins.View) = pickers[v.ordinal].launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun setMode(m: Mode) {
        mode = m
        for (i in 0 until 3) pinViews[i].visibility = if (m.ordinal == i) View.VISIBLE else View.GONE
        glView.visibility = if (m == Mode.VIEW3D) View.VISIBLE else View.GONE
        uvView.visibility = if (m == Mode.UV) View.VISIBLE else View.GONE
        for ((k, b) in modeButtons) b.alpha = if (k == m) 1f else 0.6f
        if (m == Mode.VIEW3D || m == Mode.UV) refreshPreview()
    }

    private fun loadPhoto(v: BodyPins.View, uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Finding body…"
        worker.execute {
            try {
                val bmp = Photos.decode(this, uri)
                val t = template ?: assets.open("body_template.txt").use { BodyTemplate.parse(it) }.also { template = it }
                val det = detector ?: PoseDetector(this).also { detector = it }
                val r = det.detect(bmp)
                val pins = if (r != null) BodyPins.fromDetection(v, r.points, r.visibility, t, bmp.width, bmp.height, r.mask)
                    else BodyPins.defaultPoints(v, t, bmp.width, bmp.height)
                val overlay = r?.mask?.let { PoseDetector.maskOverlay(it) }
                runOnUiThread {
                    val i = v.ordinal
                    photos[i] = bmp; masks[i] = r?.mask; detectedPins[i] = pins
                    pinViews[i].set(bmp, BodyPins.pins(v), pins, BodyPins.lines(v), overlay)
                    pinViews[i].showOverlay = useOutline
                    built = null
                    busy = false
                    status.text = (if (r != null) "Body found." else "No body detected – a template skeleton was placed.") +
                        " Drag pins onto the joints (blue = person's left, pink = person's right). Pinch to zoom."
                    setMode(Mode.values()[i])
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load photo: ${e.message}"; busy = false }
            }
        }
    }

    private fun resetPins() {
        val i = mode.ordinal
        if (i > 2) return
        detectedPins[i]?.let { pinViews[i].setPoints(it) }
    }

    private fun ensureBuilt(done: (List<MeshPart>) -> Unit) {
        built?.let { done(it); return }
        val frontPhoto = photos[0]
        val t = template
        if (frontPhoto == null || t == null) { status.text = "Add a front photo first."; return }
        if (busy) return
        val height = heightField.text.toString().toFloatOrNull()?.coerceIn(50f, 250f) ?: 175f
        val inputs = (0 until 3).map { i ->
            photos[i]?.let { BodyFitter.ViewInput(views[i], pinViews[i].points, it.width, it.height, if (useOutline) masks[i] else null) }
        }
        val headMesh = if (useHead) HeadStore.mesh else null
        val headTex = HeadStore.texture
        busy = true
        status.text = "Fitting body and baking texture…"
        worker.execute {
            try {
                val head = if (headMesh != null && headTex != null) BodyFitter.HeadInput(headMesh.positions, headMesh.tris) else null
                val res = BodyFitter(t).fit(inputs[0]!!, inputs[1], inputs[2], height, head)
                val tex = bakeBody(t, res)
                val parts = ArrayList<MeshPart>()
                parts.add(MeshPart("body", res.positions, res.normals, t.uv, res.tris, tex))
                if (head != null && res.headPositions != null) parts.add(MeshPart("head", res.headPositions, res.headNormals!!, headMesh!!.uvs, headMesh.tris, headTex))
                runOnUiThread {
                    built = parts
                    busy = false
                    val tris = parts.sumOf { it.tris.size / 3 }
                    status.text = "$tris triangles, ${height.toInt()} cm tall" +
                        (if (useHead && head == null) ". No FaceDroid head yet – build one on the face screen to use it." else "")
                    done(parts)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Build failed: ${e.message}"; busy = false }
            }
        }
    }

    private fun bakeBody(t: BodyTemplate, res: BodyFitter.Result): Bitmap {
        val front = photos[0]!!
        val frontColour = masks[0]?.let { Baker.maskColour(front, it) }
        val bakeViews = (0 until 3).map { i ->
            val p = photos[i]; val px = res.viewPx[i]
            if (p == null || px == null) null else {
                val gain = if (i == 0 || frontColour == null) null else masks[i]?.let { m -> Baker.maskColour(p, m) }?.let { Baker.gain(it, frontColour) }
                Baker.View(p, px, gain)
            }
        }
        val tex = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        Baker.drawTriangles(tex, t.uv, res.tris, res.triView, bakeViews)
        return Baker.fillGaps(tex)
    }

    private fun refreshPreview() {
        ensureBuilt { parts ->
            when (mode) {
                Mode.VIEW3D -> glView.setModel(RenderModel.fromParts(parts))
                Mode.UV -> uvView.setImageBitmap(parts.first().texture)
                else -> {}
            }
        }
    }

    private fun export() {
        ensureBuilt { parts ->
            busy = true
            status.text = "Saving…"
            val name = "body_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            worker.execute {
                val msg = try {
                    lastExport = ObjExporter.export(this, parts, name)
                    "Saved $name.obj, .mtl and textures to Download/${ObjExporter.FOLDER}"
                } catch (e: Throwable) { "Export failed: ${e.message}" }
                runOnUiThread { busy = false; status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
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
