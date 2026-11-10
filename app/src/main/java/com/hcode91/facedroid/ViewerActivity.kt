package com.hcode91.facedroid

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Spins any .obj around. Pick the .obj together with its texture (and .mtl if you like). */
class ViewerActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var glView: FaceGLView
    private lateinit var status: TextView

    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) load(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 22)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = bar.addView(Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        })
        button("Open files") { openFiles.launch(arrayOf("*/*")) }
        button("My exports") { showExports() }
        button("Reset view") { glView.resetView() }
        button("Back") { finish() }
        root.addView(HorizontalScrollView(this).apply { addView(bar) })
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            text = "Open an .obj – select its .png/.jpg texture at the same time to see it textured."
        }
        root.addView(status)
        glView = FaceGLView(this)
        root.addView(glView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val start = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { start.add(it) }
        val extra = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_URIS)
        extra?.let { start.addAll(it) }
        if (start.isNotEmpty()) load(start)
    }

    private fun load(uris: List<Uri>) {
        status.text = "Loading…"
        worker.execute {
            val msg = try {
                val named = uris.map { it to displayName(it) }
                val obj = named.firstOrNull { it.second.endsWith(".obj", true) }
                    ?: named.firstOrNull { !isImage(it.second) && !it.second.endsWith(".mtl", true) }
                    ?: throw IllegalArgumentException("No .obj file selected")
                val result = contentResolver.openInputStream(obj.first)!!.use { ObjLoader.load(it) }
                if (result.parts.isEmpty()) throw IllegalArgumentException("No faces found in ${obj.second}")

                val texNames = HashMap<String, String>()
                for (m in named.filter { it.second.endsWith(".mtl", true) })
                    contentResolver.openInputStream(m.first)!!.use { texNames.putAll(ObjLoader.textureNames(it)) }
                val images = named.filter { isImage(it.second) }
                val decoded = HashMap<Uri, Bitmap>()
                fun image(u: Uri) = decoded.getOrPut(u) { decode(u) }

                val used = ArrayList<String>()
                val parts = result.parts.map { (material, data) ->
                    val wanted = texNames[material]
                    val pick = images.firstOrNull { wanted != null && it.second.equals(wanted, true) }
                        ?: images.firstOrNull { it.second.substringBeforeLast('.').endsWith("_$material", true) }
                        ?: if (result.parts.size == 1) images.firstOrNull() else null
                    pick?.let { used.add(it.second) }
                    RenderModel.Part(data, pick?.let { image(it.first) })
                }
                RenderModel.normalise(parts.map { it.data })
                val model = RenderModel(parts)
                runOnUiThread { glView.setModel(model) }
                buildString {
                    append(obj.second).append(" – ").append(model.triangleCount).append(" triangles")
                    when {
                        used.isNotEmpty() -> append(", textures: ").append(used.joinToString())
                        result.hasUvs -> append(". No texture picked – open the .obj and its images together.")
                        else -> append(", no UVs")
                    }
                }
            } catch (e: Throwable) {
                "Couldn't open: ${e.message}"
            }
            runOnUiThread { status.text = msg }
        }
    }

    /** Lists the .obj files FaceDroid saved to Download/FaceDroid. */
    private fun showExports() {
        worker.execute {
            val files = HashMap<String, Uri>()
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            contentResolver.query(
                coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%${ObjExporter.FOLDER}%"), null
            )?.use { c ->
                while (c.moveToNext()) files[c.getString(1)] = ContentUris.withAppendedId(coll, c.getLong(0))
            }
            val objs = files.keys.filter { it.endsWith(".obj") }.sortedDescending()
            runOnUiThread {
                if (objs.isEmpty()) { status.text = "No exports yet – use Export on the main screen."; return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle("FaceDroid exports")
                    .setItems(objs.toTypedArray()) { _, i ->
                        val base = objs[i].removeSuffix(".obj")
                        load(files.filterKeys { it == objs[i] || it.startsWith(base + "_") || it == "$base.png" || it == "$base.mtl" }.values.toList())
                    }
                    .show()
            }
        }
    }

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp")

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "model.obj"
    }

    private fun decode(uri: Uri): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = minOf(1f, 4096f / maxOf(info.size.width, info.size.height))
            if (s < 1f) dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { glView.onPause(); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_URIS = "uris"
    }
}
