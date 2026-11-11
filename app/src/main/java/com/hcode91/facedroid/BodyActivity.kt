package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
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
 * Full body from a front photo (required) plus optional side and back photos, in four steps:
 * photos, pins, 3D model, measurements. Uses the head from the 3D head screen if one was built.
 */
class BodyActivity : ComponentActivity() {

    companion object {
        const val EXTRA_GOAL = "goal"
        const val GOAL_MEASURE = "measure"
        private const val PHOTOS = 0; private const val PINS = 1; private const val MODEL = 2; private const val MEASURE = 3
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var detector: PoseDetector? = null
    @Volatile private var template: BodyTemplate? = null
    private var measureFirst = false

    private val views = BodyPins.View.values()
    private val viewNames = listOf("Front", "Side", "Back")
    private val photos = arrayOfNulls<Bitmap>(3)
    private val masks = arrayOfNulls<Mask>(3)
    private val detectedPins = arrayOfNulls<FloatArray>(3)

    private lateinit var steps: StepBar
    private lateinit var status: TextView
    private lateinit var photosPane: View
    private lateinit var tiles: List<PhotoTile>
    private lateinit var heightField: EditText
    private lateinit var pinViews: Array<PointPinView>
    private lateinit var pinTabs: List<Button>
    private lateinit var glView: FaceGLView
    private lateinit var uvView: ImageView
    private lateinit var measurePane: LinearLayout
    private lateinit var measureView: MeasureView
    private lateinit var measureList: ListView
    private val optionRows = ArrayList<View>()
    private lateinit var outlineButton: Button
    private lateinit var view3dButton: Button
    private lateinit var viewTexButton: Button
    private lateinit var headButton: Button
    private lateinit var texButton: Button
    private lateinit var viewerButton: Button
    private lateinit var unitButton: Button
    private lateinit var linesButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button

    private var step = PHOTOS
    private var pinTab = 0
    private var showTexture = false
    private var useHead = true
    private var useOutline = true
    private var skinBase = false
    private var built: List<MeshPart>? = null
    /** The model as built from the photos, and the editor state applied on top of it (if edited). */
    private var editBase: List<MeshPart>? = null
    private var editState: EditState? = null
    private var lastExport: List<Uri> = emptyList()
    private var busy = false

    private var inches = false
    private var results: List<MeasurementResult> = emptyList()
    private var measureWarnings: List<String> = emptyList()
    private var measuredHeight: Float? = null
    /** List rows: a String is a group heading, an Int is an index into [results]. */
    private var rows: List<Any> = emptyList()

    private val pickers = views.map { v ->
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadPhoto(v, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        measureFirst = intent.getStringExtra(EXTRA_GOAL) == GOAL_MEASURE
        buildUi()
        setStep(PHOTOS)
        worker.execute { template = assets.open("body_template.txt").use { BodyTemplate.parse(it) } }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(Ui.titleBar(this, if (measureFirst) "Body measurements" else "3D body"))
        steps = StepBar(this, listOf("Photos", "Pins", "3D model", "Measure")) { setStep(it) }
        root.addView(steps)
        status = Ui.hint(this)
        root.addView(status)

        // ---- content
        val stage = FrameLayout(this)
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        fun pick(i: Int) = pickers[i].launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        tiles = listOf(
            PhotoTile(this, "Front photo", "Needed – whole body, facing the camera") { pick(0) },
            PhotoTile(this, "Side photo", if (measureFirst) "Needed for waist, chest and hip sizes" else "Optional – gives real depth") { pick(1) },
            PhotoTile(this, "Back photo", "Optional – textures the back of the model") { pick(2) },
        )
        heightField = EditText(this).apply {
            setText("175"); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); minEms = 3
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { built = null }
            })
        }
        photosPane = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                for (t in tiles) addView(t)
                addView(Ui.row(context).apply {
                    setPadding(Ui.dp(context, 16f), Ui.dp(context, 4f), Ui.dp(context, 16f), 0)
                    addView(TextView(context).apply { text = "Person's height"; setTextColor(Color.WHITE); textSize = 16f })
                    addView(heightField)
                    addView(Ui.label(context, "cm – sets the real-world size"))
                })
                addView(Ui.hint(context).apply { text = "Tips: stand straight with arms a little away from the body and feet apart, in fitted clothes. Take each photo from the same distance, at about waist height. Only measure adults who've agreed to it." })
            })
        }
        stage.addView(photosPane, full)
        pinViews = Array(3) { PointPinView(this).apply { onChanged = { built = null }; stage.addView(this, full) } }
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

        // ---- options for each step (only the current step's row is shown)
        fun options(build: LinearLayout.() -> Unit): View = Ui.scroll(Ui.row(this).apply(build)).also { optionRows.add(it); root.addView(it) }
        options { }
        options {
            addView(Ui.label(context, "Photo"))
            pinTabs = viewNames.mapIndexed { i, n -> Ui.secondary(context, n) { pinTab = i; showPins() }.also { addView(it) } }
            addView(Ui.label(context, "  "))
            outlineButton = Ui.secondary(context, "Use outline") {
                useOutline = !useOutline; built = null
                pinViews.forEach { it.showOverlay = useOutline }
                Ui.setChecked(outlineButton, useOutline)
                status.text = if (useOutline) "The blue outline is used to match the body's width and depth."
                    else "Outline ignored: the model's shape comes from the pins only. Use this if the outline is wrong (busy background, loose clothes)."
            }
            Ui.setChecked(outlineButton, true)
            addView(outlineButton)
            addView(Ui.secondary(context, "Reset pins") { detectedPins[pinTab]?.let { pinViews[pinTab].setPoints(it) } })
        }
        options {
            addView(Ui.label(context, "Show"))
            view3dButton = Ui.secondary(context, "3D model") { showTexture = false; showModel() }
            viewTexButton = Ui.secondary(context, "Texture") { showTexture = true; showModel() }
            addView(view3dButton); addView(viewTexButton)
            addView(Ui.label(context, "  Head"))
            headButton = Ui.secondary(context, "") { useHead = !useHead; built = null; showModel() }
            addView(headButton)
            addView(Ui.label(context, "  Skin"))
            texButton = Ui.secondary(context, "") { skinBase = !skinBase; built = null; showModel() }
            addView(texButton)
            addView(Ui.label(context, "  Then"))
            addView(Ui.secondary(context, "Edit shape") { openEditor() })
            viewerButton = Ui.secondary(context, "Open saved file") {
                startActivity(Intent(this@BodyActivity, ViewerActivity::class.java).putParcelableArrayListExtra(ViewerActivity.EXTRA_URIS, ArrayList(lastExport)))
            }
            viewerButton.visibility = View.GONE
            addView(viewerButton)
        }
        options {
            unitButton = Ui.secondary(context, "Units: cm") {
                inches = !inches
                unitButton.text = if (inches) "Units: inches" else "Units: cm"
                select(measureView.selected)
            }
            addView(unitButton)
            linesButton = Ui.secondary(context, "Lines: all") {
                measureView.showAll = !measureView.showAll
                linesButton.text = if (measureView.showAll) "Lines: all" else "Lines: selected only"
            }
            addView(linesButton)
            addView(Ui.secondary(context, "Bra size from a tape measure") { tapeDialog() })
        }

        // ---- bottom bar: back one step, and the one main action
        val bottom = Ui.row(this).apply { setPadding(Ui.dp(context, 8f), Ui.dp(context, 2f), Ui.dp(context, 8f), Ui.dp(context, 8f)) }
        backButton = Ui.secondary(this, "‹ Back") { setStep(if (step == MEASURE && measureFirst) PINS else step - 1) }
        nextButton = Ui.primary(this, "Next") {
            when (step) {
                PHOTOS -> setStep(PINS)
                PINS -> setStep(if (measureFirst) MEASURE else MODEL)
                MODEL -> export()
                else -> exportMeasurements()
            }
        }
        bottom.addView(backButton)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(nextButton)
        root.addView(bottom)
        setContentView(root)
    }

    private fun setStep(target: Int) {
        var s = target
        if (s > PHOTOS && photos[0] == null) { s = PHOTOS; status.text = "Choose a front photo first." }
        else status.text = when (s) {
            PHOTOS -> if (measureFirst) "Choose a front and a side photo and enter the height." else "Choose a front photo. Side and back photos are optional but improve the model."
            PINS -> "Check the pins sit on the joints (blue = the person's left, pink = their right). Drag any that are off. Pinch to zoom."
            MODEL -> "Drag to turn, pinch to zoom."
            else -> "Measuring…"
        }
        step = s
        steps.select(s)
        for (i in PINS..MEASURE) steps.setUsable(i, photos[0] != null)
        photosPane.visibility = if (s == PHOTOS) View.VISIBLE else View.GONE
        measurePane.visibility = if (s == MEASURE) View.VISIBLE else View.GONE
        for ((i, row) in optionRows.withIndex()) row.visibility = if (i == s) View.VISIBLE else View.GONE
        backButton.visibility = if (s == PHOTOS) View.INVISIBLE else View.VISIBLE
        nextButton.text = when (s) {
            PHOTOS -> "Next: check pins"
            PINS -> if (measureFirst) "Next: measure" else "Next: build 3D model"
            MODEL -> "Save OBJ"
            else -> "Save report"
        }
        Ui.setUsable(nextButton, photos[0] != null)
        if (s == PINS) { if (photos[pinTab] == null) pinTab = 0; showPins() } else pinViews.forEach { it.visibility = View.GONE }
        if (s == MODEL) showModel() else { glView.visibility = View.GONE; uvView.visibility = View.GONE }
        if (s == MEASURE) runMeasure()
    }

    private fun showPins() {
        for (i in 0 until 3) {
            pinViews[i].visibility = if (i == pinTab) View.VISIBLE else View.GONE
            Ui.setChecked(pinTabs[i], i == pinTab)
            pinTabs[i].visibility = if (photos[i] != null) View.VISIBLE else View.GONE
        }
    }

    private fun showModel() {
        glView.visibility = if (showTexture) View.GONE else View.VISIBLE
        uvView.visibility = if (showTexture) View.VISIBLE else View.GONE
        Ui.setChecked(view3dButton, !showTexture); Ui.setChecked(viewTexButton, showTexture)
        headButton.text = if (useHead) "From 3D head screen" else "Built-in"
        texButton.text = if (skinBase) "Plain skin (no clothes)" else "From photos"
        ensureBuilt { parts ->
            if (step != MODEL) return@ensureBuilt
            if (showTexture) uvView.setImageBitmap(parts.first().texture) else glView.setModel(RenderModel.fromParts(parts))
        }
    }

    private fun loadPhoto(v: BodyPins.View, uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Finding the body…"
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
                    tiles[i].set(bmp, if (r != null) "Chosen ✓ – body found" else "Chosen – no body found, place the pins by hand")
                    built = null
                    busy = false
                    pinTab = i
                    setStep(PHOTOS)
                    status.text = if (r != null) "Body found in the ${viewNames[i].lowercase()} photo. Add more photos or tap Next."
                        else "No body was detected in that photo, so a template skeleton was placed. You'll need to drag the pins onto the joints in the next step."
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't load that photo: ${e.message}"; busy = false }
            }
        }
    }

    // ---- 3D model ----------------------------------------------------------------------------------

    private fun ensureBuilt(done: (List<MeshPart>) -> Unit) {
        built?.let { done(it); return }
        val frontPhoto = photos[0]
        val t = template
        if (frontPhoto == null || t == null) { status.text = "Choose a front photo first."; return }
        if (busy) return
        val height = heightField.text.toString().toFloatOrNull()?.coerceIn(50f, 250f) ?: 175f
        val inputs = (0 until 3).map { i ->
            photos[i]?.let { BodyFitter.ViewInput(views[i], pinViews[i].points, it.width, it.height, if (useOutline) masks[i] else null) }
        }
        val headMesh = if (useHead) HeadStore.mesh else null
        val headTex = HeadStore.texture
        busy = true
        status.text = "Building the model…"
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
                    editBase = null; editState = null
                    busy = false
                    status.text = "Drag to turn, pinch to zoom. Happy with it? Tap Save OBJ." +
                        (if (useHead && head == null) " No head from the 3D head screen yet, so the built-in head is used." else "")
                    done(parts)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Couldn't build the model: ${e.message}"; busy = false }
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
        if (skinBase) {
            // Plain skin everywhere (no clothes); only a built-in head keeps its photo pixels.
            val tex = Baker.skinBase(2048, t.uv, res.tris, res.normals, skinColour(front))
            val headOnly = IntArray(res.triView.size) { i ->
                if ((0 until 3).any { t.region[res.tris[i * 3 + it]].toInt() == BodyTemplate.HEAD }) res.triView[i] else -1
            }
            Baker.drawTriangles(tex, t.uv, res.tris, headOnly, bakeViews)
            return tex
        }
        val tex = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        Baker.drawTriangles(tex, t.uv, res.tris, res.triView, bakeViews)
        return Baker.fillGaps(tex)
    }

    /** Skin tone from the cheeks: the 3D head's texture if it's in use, else the front photo. */
    private fun skinColour(front: Bitmap): Int {
        val samples = ArrayList<Int>()
        val hm = if (useHead) HeadStore.mesh else null
        val ht = HeadStore.texture
        if (hm != null && ht != null) for (lm in intArrayOf(50, 280)) {
            Baker.patchColour(ht, hm.uvs[lm * 2] * ht.width, (1f - hm.uvs[lm * 2 + 1]) * ht.height, maxOf(3, ht.width / 200))?.let { samples.add(it) }
        }
        if (samples.isEmpty()) {
            val ids = BodyPins.ids(BodyPins.View.FRONT); val pts = pinViews[0].points
            val nose = ids.indexOf(0)
            for (eyeId in intArrayOf(2, 5)) {
                val e = ids.indexOf(eyeId)
                if (nose < 0 || e < 0) continue
                Baker.patchColour(front, pts[e * 2], pts[nose * 2 + 1], maxOf(2, front.width / 300))?.let { samples.add(it) }
            }
        }
        if (samples.isEmpty()) return Color.rgb(200, 160, 135)
        return Color.rgb(samples.sumOf { Color.red(it) } / samples.size, samples.sumOf { Color.green(it) } / samples.size, samples.sumOf { Color.blue(it) } / samples.size)
    }

    private fun openEditor() {
        ensureBuilt { parts ->
            val t = template
            val base = editBase ?: parts
            editBase = base
            EditStore.parts = base
            EditStore.state = editState
            EditStore.roles = base.map { p -> if (p.name == "body") t?.region else ByteArray(p.positions.size / 3) { EditModel.ROLE_HEAD_PART } }
            startActivity(Intent(this, EditorActivity::class.java))
        }
    }

    private fun export() {
        ensureBuilt { parts ->
            busy = true
            status.text = "Saving…"
            val name = "body_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            worker.execute {
                val ok = try { lastExport = ObjExporter.export(this, parts, name); true } catch (e: Throwable) { false }
                val msg = if (ok) "Saved $name.obj with its textures to Download/${ObjExporter.FOLDER}" else "Couldn't save the file."
                runOnUiThread {
                    busy = false; status.text = msg
                    if (ok) viewerButton.visibility = View.VISIBLE
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
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
            val tv = (convertView as? TextView) ?: TextView(this@BodyActivity).apply { setPadding((16 * dp).toInt(), (7 * dp).toInt(), (16 * dp).toInt(), (7 * dp).toInt()) }
            val row = rows[position]
            if (row is String) {
                tv.text = row; tv.setTextColor(Ui.ACCENT); tv.setTypeface(null, Typeface.BOLD); tv.setBackgroundColor(Ui.SURFACE)
                return tv
            }
            val r = results[row as Int]
            tv.setTypeface(null, Typeface.NORMAL)
            tv.setBackgroundColor(if (row == measureView.selected) Ui.ACCENT_DIM else Color.TRANSPARENT)
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

    private fun runMeasure() {
        val fp = photos[0] ?: return
        if (busy) return
        val height = heightField.text.toString().toFloatOrNull()?.takeIf { it in 50f..250f }
        val frontIn = BodyFitter.ViewInput(BodyPins.View.FRONT, pinViews[0].points, fp.width, fp.height, masks[0])
        val sp = photos[1]
        val sideIn = sp?.let { BodyFitter.ViewInput(BodyPins.View.SIDE, pinViews[1].points, it.width, it.height, masks[1]) }
        busy = true
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
                    status.text = "$have of ${res.size} measurements found. Tap a row or a line to see it. ± is the likely error; clothes count as body." +
                        eng.warnings.joinToString("") { "\n• $it" }
                }
            } catch (e: Throwable) {
                runOnUiThread { busy = false; status.text = "Couldn't measure: ${e.message}" }
            }
        }
    }

    private fun exportMeasurements() {
        val res = results
        if (res.isEmpty()) { status.text = "Nothing to save yet."; return }
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
                "Saved $name as PDF, CSV and JSON to Download/${ObjExporter.FOLDER}"
            } catch (e: Throwable) { "Couldn't save the report." }
            runOnUiThread { busy = false; status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
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
            val pad = Ui.dp(context, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(bust); addView(under); addView(out)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
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
        android.app.AlertDialog.Builder(this).setTitle("Bra size from a tape measure").setView(box).setPositiveButton("Close", null).show()
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val r = EditStore.result
        if (r != null && r.base === editBase) {
            EditStore.result = null
            built = r.edited; editState = r.state
            if (step == MODEL) showModel()
            status.text = "Showing your edited shape. Save OBJ uses it. Changing pins, photos, height, head or skin rebuilds from the photos and drops the edits."
        }
    }

    override fun onPause() { glView.onPause(); super.onPause() }

    override fun onDestroy() {
        worker.execute { detector?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
