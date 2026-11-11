package com.hcode91.facedroid

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.OutputStream

/** Writes the measurement table as a simple A4 PDF report. */
object MeasurePdf {
    fun write(out: OutputStream, results: List<MeasurementResult>, warnings: List<String>, heightCm: Float?, inches: Boolean, generated: String) {
        val doc = PdfDocument()
        val pw = 595; val ph = 842; val margin = 40f
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            y = margin + 10
        }
        fun need(space: Float) { if (page == null || y + space > ph - margin) newPage() }
        fun wrap(s: String, p: TextPaint, x: Float, maxW: Float, lineH: Float) {
            var rest = s
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, maxW, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp + 1 }
                need(lineH)
                page!!.canvas.drawText(rest.substring(0, n).trimEnd(), x, y, p)
                y += lineH
                rest = rest.substring(n)
            }
        }
        newPage()
        page!!.canvas.drawText("FaceDroid body measurements", margin, y, title); y += 20
        wrap("$generated · height " + (heightCm?.let { "${it.toInt()} cm (entered)" } ?: "not entered"), body, margin, pw - 2 * margin, 13f)
        wrap("Estimated from photos. ± is roughly one standard deviation and does not allow for clothing: the clothed outline is what gets measured. Not tailoring- or medical-grade.", small, margin, pw - 2 * margin, 10f)
        for (wn in warnings) wrap("! $wn", small, margin, pw - 2 * margin, 10f)
        y += 6
        var group = ""
        for (r in results) {
            if (r.def.group != group) {
                group = r.def.group
                need(40f); y += 8
                page!!.canvas.drawText(group, margin, y, head); y += 14
            }
            need(26f)
            page!!.canvas.drawText(r.def.name, margin, y, body)
            page!!.canvas.drawText(if (r.value == null) "—" else MeasureFormat.value(r, inches), 300f, y, body)
            page!!.canvas.drawText(if (r.value == null) "unavailable" else r.status.name.lowercase() + " · " + (r.reliability?.label ?: ""), 440f, y, small)
            y += 11
            if (r.note.isNotEmpty()) wrap(r.note, small, margin + 8, pw - 2 * margin - 8, 10f)
            y += 3
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}
