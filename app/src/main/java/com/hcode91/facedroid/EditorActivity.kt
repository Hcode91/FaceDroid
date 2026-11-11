package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Hand-over point for the model to edit (set by the face, body or viewer screen before starting the editor). */
object EditStore {
    @Volatile var parts: List<MeshPart>? = null
    /** Body-area role per vertex for each part (see [EditPart]), or null for a plain model. */
    @Volatile var roles: List<ByteArray?>? = null
    /** Slider and brush state from an earlier visit, so editing carries on where it left off. */
    @Volatile var state: EditState? = null
    /** Written when the editor is left: the screen that opened it picks this up and shows the edited model. */
    @Volatile var result: EditResult? = null
}

class EditState(val params: FloatArray, val deltas: List<FloatArray>)
class EditResult(val base: List<MeshPart>, val edited: List<MeshPart>, val state: EditState)

/**
 * Reshapes a model: sliders for the whole model and (for FaceDroid bodies) each body area, plus a
 * push / pull / smooth brush. UVs and textures are untouched. Saves as a new OBJ.
 */
class EditorActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var glView: FaceGLView
    private lateinit var status: TextView
    private lateinit var sliderPane: View
    private lateinit var brushPane: View
    private lateinit var slidersButton: Button
    private lateinit var brushButton: Button
    private lateinit var mirrorButton: Button
    private val brushModeButtons = ArrayList<Button>()
    private val sliderBars = ArrayList<Pair<Int, SeekBar>>()

    private var source: List<MeshPart> = emptyList()
    private lateinit var model: EditModel
    private var brushing = false
    private var brushMode = EditModel.PUSH
    private var brushSize = 0.12f     // fraction of the model's radius
    private var brushStrength = 0.5f
    private var mirror = false
    private var refreshQueued = false
    private var busy = false
    private var lastExport: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parts = EditStore.parts
        if (parts.isNullOrEmpty()) { finish(); return }
        source = parts
        val roles = EditStore.roles
        model = EditModel(parts.mapIndexed { i, p -> EditPart(p.name, p.positions, p.tris, roles?.getOrNull(i)) })
        EditStore.result = null
        buildUi()
        EditStore.state?.let { st ->
            if (st.deltas.size == model.parts.size && st.deltas.indices.all { st.deltas[it].size == model.parts[it].delta.size }) {
                for ((i, p) in model.parts.withIndex()) p.delta = st.deltas[i].copyOf()
                st.params.copyInto(model.params)
                model.recompute()
                for ((key, sb) in sliderBars) sb.progress = Math.round((st.params[key] - 0.7f) / 0.005f).coerceIn(0, 120)
            }
        }
        glView.setModel(RenderModel(parts.indices.map { RenderModel.Part(soup(it, model.parts[it].normals()), parts[it].texture) }))
        setBrushing(false)
    }

    /** Vertex data for the 3D view: the edited part, centred and scaled the same way throughout the session. */
    private fun soup(i: Int, normals: FloatArray): FloatArray {
        val p = model.parts[i]; val uv = source[i].uvs
        val k = 10f / model.radius
        val cy = model.centre[1]
        val out = FloatArray(p.tris.size * RenderModel.STRIDE)
        for ((n, v) in p.tris.withIndex()) {
            val o = n * RenderModel.STRIDE
            out[o] = (p.cur[v * 3] - model.centre[0]) * k; out[o + 1] = (p.cur[v * 3 + 1] - cy) * k; out[o + 2] = (p.cur[v * 3 + 2] - model.centre[2]) * k
            out[o + 3] = normals[v * 3]; out[o + 4] = normals[v * 3 + 1]; out[o + 5] = normals[v * 3 + 2]
            out[o + 6] = uv[v * 2]; out[o + 7] = uv[v * 2 + 1]
        }
        return out
    }

    private fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        glView.post {
            refreshQueued = false
            glView.updateGeometry(model.parts.indices.map { soup(it, model.parts[it].normals()) })
        }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 22)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun LinearLayout.button(label: String, onClick: () -> Unit) = Ui.secondary(this@EditorActivity, label, onClick).also { this@button.addView(it) }
        bar.addView(Ui.label(this, "Tool"))
        slidersButton = bar.button("Sliders") { setBrushing(false) }
        brushButton = bar.button("Brush") { setBrushing(true) }
        bar.addView(Ui.label(this, "  "))
        bar.button("Undo stroke") { if (model.undo()) refresh() else status.text = "Nothing to undo (undo covers brush strokes; sliders can just be moved back)." }
        bar.button("Reset all") {
            model.resetAll()
            for ((_, sb) in sliderBars) sb.progress = 60
            refresh()
        }
        bar.button("Reset view") { glView.resetView() }
        bar.button("Open saved models") {
            startActivity(Intent(this, ViewerActivity::class.java).apply {
                if (lastExport.isNotEmpty()) putParcelableArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(lastExport))
            })
        }
        root.addView(Ui.titleBar(this, "Edit shape"))
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(Ui.dp(context, 8f), 0, Ui.dp(context, 8f), 0); addView(bar) })

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        }
        root.addView(status)

        glView = FaceGLView(this).apply { onBrush = { phase, x, y -> stroke(phase, x, y) } }
        root.addView(glView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- sliders
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0) }
        fun slider(label: String, onChange: (Float) -> Unit, key: Int = -1, start: Int = 60, show: (Int) -> String = { "${70 + it / 2}%" }) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(this).apply { setTextColor(Color.WHITE); text = "$label ${show(start)}"; width = (150 * dp).toInt() }
            val sb = SeekBar(this).apply {
                max = 120; progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tv.text = "$label ${show(p)}"; onChange(0.7f + p * 0.005f) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(tv); row.addView(sb, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            list.addView(row)
            if (key >= 0) sliderBars.add(key to sb)
        }
        fun param(label: String, key: Int) = slider(label, { model.params[key] = it; model.recompute(); refresh() }, key)
        fun heading(text: String) = list.addView(TextView(this).apply { this.text = text; setTextColor(Color.rgb(0, 230, 255)); setPadding(0, (8 * dp).toInt(), 0, 0) })
        heading("Whole model")
        param("Width", EditModel.WIDTH); param("Depth", EditModel.DEPTH); param("Height", EditModel.HEIGHT)
        if (model.hasBody) {
            heading("Body areas (thinner ← → thicker)")
            param("Head size", EditModel.HEAD); param("Shoulders", EditModel.SHOULDERS); param("Chest", EditModel.CHEST)
            param("Waist", EditModel.WAIST); param("Hips", EditModel.HIPS)
            param("Arm thickness", EditModel.ARM_THICK); param("Arm length", EditModel.ARM_LEN)
            param("Leg thickness", EditModel.LEG_THICK); param("Leg length", EditModel.LEG_LEN)
        } else heading("Body-area sliders appear for bodies made on the Body screen.")
        sliderPane = ScrollView(this).apply { addView(list) }
        root.addView(sliderPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (230 * dp).toInt()))

        // ---- brush
        val brush = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (6 * dp).toInt()) }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in listOf("Push out", "Pull in", "Smooth").withIndex()) brushModeButtons.add(modes.button(name) { brushMode = i; updateButtons() })
        mirrorButton = modes.button("Mirror left/right") { mirror = !mirror; updateButtons() }
        brush.addView(HorizontalScrollView(this).apply { addView(modes) })
        fun brushSlider(label: String, start: Int, onChange: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(this).apply { setTextColor(Color.WHITE); text = "$label $start%"; width = (150 * dp).toInt() }
            val sb = SeekBar(this).apply {
                max = 100; progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tv.text = "$label $p%"; onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(tv); row.addView(sb, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            brush.addView(row)
        }
        brushSlider("Brush size", 40) { brushSize = 0.02f + it * 0.0025f }
        brushSlider("Strength", 50) { brushStrength = it / 100f }
        brushPane = brush
        root.addView(brushPane)
        val bottom = Ui.row(this).apply { setPadding(Ui.dp(context, 8f), Ui.dp(context, 2f), Ui.dp(context, 8f), Ui.dp(context, 8f)) }
        bottom.addView(Ui.secondary(this, "‹ Done (keep edits)") { finish() })
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(Ui.primary(this, "Save as new OBJ") { save() })
        root.addView(bottom)
        setContentView(root)
    }

    private fun updateButtons() {
        Ui.setChecked(slidersButton, !brushing)
        Ui.setChecked(brushButton, brushing)
        for ((i, b) in brushModeButtons.withIndex()) Ui.setChecked(b, i == brushMode)
        Ui.setChecked(mirrorButton, mirror)
    }

    private fun setBrushing(on: Boolean) {
        brushing = on
        glView.brushMode = on
        sliderPane.visibility = if (on) View.GONE else View.VISIBLE
        brushPane.visibility = if (on) View.VISIBLE else View.GONE
        status.text = if (on) "Brush: drag one finger over the model to sculpt. Two fingers turn and zoom. Undo steps back one stroke."
            else "Sliders: 100% is unchanged. Drag the model to turn it, pinch to zoom. The texture stays as it is."
        updateButtons()
    }

    private fun stroke(phase: Int, x: Float, y: Float) {
        if (phase == 2) return
        if (phase == 0) model.beginStroke()
        val r = glView.ray(x, y) ?: return
        // Display space → model space.
        val k = model.radius / 10f
        val o = floatArrayOf(r[0] * k + model.centre[0], r[1] * k + model.centre[1], r[2] * k + model.centre[2])
        val hit = model.pick(o, floatArrayOf(r[3], r[4], r[5])) ?: return
        val radius = brushSize * model.radius
        // Applied on every finger movement, so each dab is small.
        val amount = if (brushMode == EditModel.SMOOTH) 0.1f + brushStrength * 0.5f else radius * (0.002f + 0.02f * brushStrength)
        model.brush(hit, radius, amount, brushMode, mirror)
        refresh()
    }

    private fun editedParts() = source.mapIndexed { i, p -> MeshPart(p.name, model.parts[i].cur.copyOf(), model.parts[i].normals(), p.uvs, p.tris, p.texture) }

    /** Hand the edited model back to the screen that opened the editor. */
    private fun publish() {
        if (!::model.isInitialized) return
        EditStore.result = EditResult(source, editedParts(), EditState(model.params.copyOf(), model.parts.map { it.delta.copyOf() }))
    }

    private fun save() {
        if (busy) return
        busy = true
        status.text = "Saving…"
        val parts = editedParts()
        val name = "edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        worker.execute {
            val msg = try {
                lastExport = ObjExporter.export(this, parts, name)
                "Saved $name.obj, .mtl and textures to Download/${ObjExporter.FOLDER}"
            } catch (e: Throwable) { "Save failed: ${e.message}" }
            runOnUiThread { busy = false; status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { publish(); if (::glView.isInitialized) glView.onPause(); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
