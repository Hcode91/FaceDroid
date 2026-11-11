package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
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

    private enum class Mode { FRONT, SIDE, BACK, VIEW3D, UV, MEASURE }

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
    private lateinit var measurePane: LinearLayout
    private lateinit var measureView: MeasureView
    private lateinit var measureList: ListView
    private lateinit var unitButton: Button
    private lateinit var linesButton: Button
    private lateinit var tapeButton: Button
    private var inches = false
    private var results: List<MeasurementResult> = emptyList()
    private var measureWarnings: List<String> = emptyList()
    private var measuredHeight: Float? = null
    /** List rows: a String is a group heading, an Int is an index into [results]. */
    private var rows: List<Any> = emptyList()

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
        modeButtons[Mode.MEASURE] = row1.button("Measure") { setMode(Mode.MEASURE) }
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
        row2.button("Edit model") {
            ensureBuilt { parts ->
                val t = template
                EditStore.parts = parts
                EditStore.roles = parts.map { p -> if (p.name == "body") t?.region else ByteArray(p.positions.size / 3) { EditModel.ROLE_HEAD_PART } }
                startActivity(Intent(this, EditorActivity::class.java))
            }
        }
        unitButton = row2.button("Units: cm") {
            inches = !inches
            unitButton.text = if (inches) "Units: in" else "Units: cm"
            select(measureView.selected)
            (measureList.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
        linesButton = row2.button("Lines: all") {
            measureView.showAll = !measureView.showAll
            linesButton.text = if (measureView.showAll) "Lines: all" else "Lines: selected"
        }
        tapeButton = row2.button("Bra size from tape") { tapeDialog() }
        row2.button("Export") { if (mode == Mode.MEASURE) exportMeasurements() else export() }
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
        measureView = MeasureView(this).apply { onPick = { i -> select(i); measureList.setSelection(rows.indexOf(i).coerceAtLeast(0)) } }
        measureList = ListView(this).apply {
            adapter = rowAdapter
            setOnItemClickListener { _, _, pos, _ -> (rows.getOrNull(pos) as? Int)?.let { select(it) } }
        }
        measurePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(measureView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f))
            addView(measureList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        stage.addView(measurePane, full)
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
        measurePane.visibility = if (m == Mode.MEASURE) View.VISIBLE else View.GONE
        unitButton.visibility = measurePane.visibility
        linesButton.visibility = measurePane.visibility
        tapeButton.visibility = measurePane.visibility
        for ((k, b) in modeButtons) b.alpha = if (k == m) 1f else 0.6f
        if (m == Mode.VIEW3D || m == Mode.UV) refreshPreview()
        if (m == Mode.MEASURE) runMeasure()
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

    // ---- measurements ------------------------------------------------------------------------------

    private val rowAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Int
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val tv = (convertView as? TextView) ?: TextView(this@BodyActivity).apply { setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt()) }
            val row = rows[position]
            if (row is String) {
                tv.text = row; tv.setTextColor(Color.rgb(0, 230, 255)); tv.setTypeface(null, Typeface.BOLD); tv.setBackgroundColor(Color.rgb(30, 30, 36))
                return tv
            }
            val r = results[row as Int]
            tv.setTypeface(null, Typeface.NORMAL)
            tv.setBackgroundColor(if (row == measureView.selected) Color.rgb(60, 54, 20) else Color.TRANSPARENT)
            if (r.value == null) {
                tv.setTextColor(Color.GRAY)
                tv.text = "${r.def.name}: —\nUnavailable · ${r.note}"
            } else {
                tv.setTextColor(Color.WHITE)
                val kind = if (r.status == MStatus.MEASURED) "Measured" else if (r.status == MStatus.RELATIVE) "Relative" else "Estimate"
                tv.text = "${r.def.name}: ${MeasureFormat.value(r, inches)}\n$kind · ${r.reliability?.label}" + (if (r.note.isNotEmpty()) " · ${r.note}" else "")
            }
            return tv
        }
    }

    private fun select(i: Int) {
        val r = results.getOrNull(i)
        measureView.label = if (r == null) "" else "${r.def.name}: ${MeasureFormat.value(r, inches)}"
        measureView.selected = i
        rowAdapter.notifyDataSetChanged()
        if (r != null) status.text = "${r.def.name} – ${r.def.method} Limits: ${r.def.limitations}"
    }

    /** Exact UK bra size from two tape measurements typed in by the user. */
    private fun tapeDialog() {
        val unit = if (inches) "in" else "cm"
        fun field(hint: String) = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val bust = field("Bust, around the fullest point ($unit)")
        val under = field("Under-bust, snug around the ribcage ($unit)")
        val out = TextView(this).apply { textSize = 18f; setPadding(0, 24, 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(bust); addView(under); addView(out)
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val k = if (inches) 2.54f else 1f
                val b = bust.text.toString().toFloatOrNull()?.times(k)
                val u = under.text.toString().toFloatOrNull()?.times(k)
                out.text = when {
                    b == null || u == null -> ""
                    u !in 50f..160f || b !in 50f..200f -> "Check the numbers (units are $unit)"
                    else -> "UK size: " + BraSize.uk(b, u)
                }
            }
        }
        bust.addTextChangedListener(watcher); under.addTextChangedListener(watcher)
        android.app.AlertDialog.Builder(this).setTitle("Bra size from tape measurements").setView(box).setPositiveButton("Close", null).show()
    }

    private fun runMeasure() {
        val fp = photos[0]
        if (fp == null) { status.text = "Add a front photo first. A side photo adds depth, which circumferences need."; return }
        if (busy) return
        val height = heightField.text.toString().toFloatOrNull()?.takeIf { it in 50f..250f }
        val frontIn = BodyFitter.ViewInput(BodyPins.View.FRONT, pinViews[0].points, fp.width, fp.height, masks[0])
        val sp = photos[1]
        val sideIn = sp?.let { BodyFitter.ViewInput(BodyPins.View.SIDE, pinViews[1].points, it.width, it.height, masks[1]) }
        busy = true
        status.text = "Measuring…"
        worker.execute {
            try {
                val eng = MeasureEngine(frontIn, sideIn, height)
                val res = eng.run()
                val list = ArrayList<Any>()
                var group = ""
                for ((i, r) in res.withIndex()) { if (r.def.group != group) { group = r.def.group; list.add(group) }; list.add(i) }
                runOnUiThread {
                    busy = false
                    results = res; rows = list; measureWarnings = eng.warnings; measuredHeight = height
                    measureView.photos = arrayOf(fp, sp); measureView.results = res
                    select(-1)
                    val have = res.count { it.value != null }
                    status.text = "$have of ${res.size} measurements available – tap a row or a line. ± is the likely error. " +
                        "The clothed outline is what gets measured. Only measure adults who've agreed to it." +
                        eng.warnings.joinToString("") { "\n• $it" }
                }
            } catch (e: Throwable) {
                runOnUiThread { busy = false; status.text = "Measuring failed: ${e.message}" }
            }
        }
    }

    private fun exportMeasurements() {
        val res = results
        if (res.isEmpty()) { status.text = "Nothing to export yet."; return }
        if (busy) return
        busy = true
        val warn = measureWarnings; val height = measuredHeight; val inch = inches
        val now = Date()
        val name = "measure_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)
        worker.execute {
            val msg = try {
                ObjExporter.save(this, "$name.json", "application/json") { it.write(MeasureFormat.json(res, warn, height, stamp).toByteArray()) }
                ObjExporter.save(this, "$name.csv", "text/csv") { it.write(MeasureFormat.csv(res).toByteArray()) }
                ObjExporter.save(this, "$name.pdf", "application/pdf") { MeasurePdf.write(it, res, warn, height, inch, stamp) }
                "Saved $name.json, .csv and .pdf to Download/${ObjExporter.FOLDER}"
            } catch (e: Throwable) { "Export failed: ${e.message}" }
            runOnUiThread { busy = false; status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
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
