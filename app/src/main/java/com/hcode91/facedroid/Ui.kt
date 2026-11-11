package com.hcode91.facedroid

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared look for every screen: colours, buttons, step bar, photo tiles. */
object Ui {
    val BG = Color.rgb(18, 18, 22)
    val SURFACE = Color.rgb(32, 32, 40)
    val LINE = Color.rgb(90, 90, 105)
    val ACCENT = Color.rgb(0, 200, 230)
    val ACCENT_DIM = Color.rgb(0, 62, 74)
    val MUTED = Color.rgb(170, 170, 180)

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    fun shape(c: Context, fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(c, radius).toFloat()
        if (stroke != null) setStroke(dp(c, 1f), stroke)
    }

    private fun base(c: Context, label: String, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; stateListAnimator = null; textSize = 14f
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(c, 16f), dp(c, 10f), dp(c, 16f), dp(c, 10f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(c, 4f), dp(c, 4f), dp(c, 4f), dp(c, 4f))
        }
    }

    /** The one main action on a screen. */
    fun primary(c: Context, label: String, onClick: () -> Unit) = base(c, label, onClick).apply {
        background = shape(c, ACCENT, 22f); setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD)
    }

    /** Other actions, and on/off or either/or options (see [setChecked]). */
    fun secondary(c: Context, label: String, onClick: () -> Unit) = base(c, label, onClick).apply {
        background = shape(c, SURFACE, 22f, LINE); setTextColor(Color.WHITE)
    }

    fun setChecked(b: Button, on: Boolean) {
        b.background = if (on) shape(b.context, ACCENT_DIM, 22f, ACCENT) else shape(b.context, SURFACE, 22f, LINE)
        b.setTextColor(if (on) ACCENT else Color.WHITE)
    }

    fun setUsable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    fun titleBar(a: Activity, title: String, showBack: Boolean = true): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(a, 4f), dp(a, 6f), dp(a, 12f), dp(a, 2f))
        if (showBack) addView(TextView(a).apply {
            text = "‹"; textSize = 30f; setTextColor(Color.WHITE); contentDescription = "Back"
            setPadding(dp(a, 14f), 0, dp(a, 14f), dp(a, 4f))
            setOnClickListener { a.finish() }
        })
        addView(TextView(a).apply {
            text = title; textSize = 20f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            if (!showBack) setPadding(dp(a, 12f), dp(a, 8f), 0, dp(a, 8f))
        })
    }

    /** One or two lines telling the user what to do right now. */
    fun hint(c: Context) = TextView(c).apply {
        setTextColor(MUTED); textSize = 13f
        setPadding(dp(c, 16f), dp(c, 6f), dp(c, 16f), dp(c, 8f))
    }

    fun row(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 8f), 0, dp(c, 8f), 0)
    }

    fun scroll(row: LinearLayout) = HorizontalScrollView(row.context).apply { isHorizontalScrollBarEnabled = false; addView(row) }

    fun label(c: Context, text: String) = TextView(c).apply { this.text = text; setTextColor(MUTED); textSize = 13f; setPadding(dp(c, 8f), 0, dp(c, 4f), 0) }
}

/** "1 Photos  2 Pins  3 Result" – shows where you are and lets you jump between steps. */
class StepBar(context: Context, labels: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val items = ArrayList<TextView>()
    private val usable = BooleanArray(labels.size) { true }
    private var current = 0

    init {
        orientation = HORIZONTAL
        setPadding(Ui.dp(context, 8f), Ui.dp(context, 4f), Ui.dp(context, 8f), Ui.dp(context, 4f))
        for ((i, l) in labels.withIndex()) {
            val tv = TextView(context).apply {
                text = "${i + 1}  $l"; gravity = Gravity.CENTER; textSize = 13f; maxLines = 1
                setPadding(0, Ui.dp(context, 9f), 0, Ui.dp(context, 9f))
                setOnClickListener { if (usable[i]) onSelect(i) }
            }
            items.add(tv)
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(Ui.dp(context, 2f), 0, Ui.dp(context, 2f), 0) })
        }
        paint()
    }

    fun select(i: Int) { current = i; paint() }
    fun setUsable(i: Int, on: Boolean) { usable[i] = on; paint() }

    private fun paint() {
        for ((i, tv) in items.withIndex()) {
            val sel = i == current
            tv.background = Ui.shape(context, if (sel) Ui.ACCENT_DIM else Ui.SURFACE, 10f, if (sel) Ui.ACCENT else null)
            tv.setTextColor(if (sel) Ui.ACCENT else Color.WHITE)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.alpha = if (usable[i]) 1f else 0.35f
        }
    }
}

/** A row for choosing one photo: thumbnail, what it's for, whether it's been chosen, and a Choose button. */
class PhotoTile(context: Context, title: String, private val emptyText: String, onChoose: () -> Unit) : LinearLayout(context) {
    private val thumb = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.shape(context, Ui.BG, 8f, Ui.LINE); clipToOutline = true }
    private val state = TextView(context).apply { setTextColor(Ui.MUTED); textSize = 13f; text = emptyText }
    private val button = Ui.secondary(context, "Choose", onChoose)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Ui.shape(context, Ui.SURFACE, 14f)
        val p = Ui.dp(context, 10f)
        setPadding(p, p, p, p)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 6f)) }
        addView(thumb, LayoutParams(Ui.dp(context, 60f), Ui.dp(context, 76f)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(p, 0, p, 0)
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
            addView(state)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(button)
    }

    fun set(photo: Bitmap?, text: String? = null) {
        thumb.setImageBitmap(photo)
        state.text = text ?: if (photo == null) emptyText else "Chosen ✓"
        state.setTextColor(if (photo == null) Ui.MUTED else Ui.ACCENT)
        button.text = if (photo == null) "Choose" else "Change"
    }
}
