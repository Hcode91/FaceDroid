package com.hcode91.facedroid

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Start screen: pick what you want to make. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dp(this@HomeActivity, 16f)) }
        list.addView(Ui.titleBar(this, "FaceDroid", showBack = false))
        list.addView(Ui.hint(this).apply { text = "What would you like to make?" })

        fun card(title: String, needs: String, gives: String, open: () -> Unit) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.shape(context, Ui.SURFACE, 16f)
                val p = Ui.dp(context, 16f)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 6f))
                }
                addView(TextView(context).apply { text = "$title  ›"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
                addView(TextView(context).apply { text = gives; textSize = 14f; setTextColor(Color.WHITE); setPadding(0, Ui.dp(context, 4f), 0, 0) })
                addView(TextView(context).apply { text = "You need: $needs"; textSize = 13f; setTextColor(Ui.MUTED); setPadding(0, Ui.dp(context, 2f), 0, 0) })
                setOnClickListener { open() }
            })
        }
        card("3D head", "a front-on face photo (a side photo is optional)", "A textured head model you can save as an OBJ.") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        card("3D body", "a full-length front photo (side and back optional)", "A textured full-body model. Make a head first to put it on the body.") {
            startActivity(Intent(this, BodyActivity::class.java))
        }
        card("Body measurements", "full-length front and side photos, and the person's height", "Waist, chest, hips, inseam and more, with a report you can save.") {
            startActivity(Intent(this, BodyActivity::class.java).putExtra(BodyActivity.EXTRA_GOAL, BodyActivity.GOAL_MEASURE))
        }
        card("Open or edit a model", "an .obj file, or something you saved here", "View it in 3D, reshape it with sliders or sculpt it with a brush.") {
            startActivity(Intent(this, ViewerActivity::class.java))
        }
        list.addView(Ui.hint(this).apply { text = "Everything you save goes to Download/${ObjExporter.FOLDER} on this phone. Nothing is uploaded." })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(list) })
    }
}
