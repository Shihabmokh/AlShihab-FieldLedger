package com.alshihab.fieldledger.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.alshihab.fieldledger.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** Renders a [ReportDoc] to PDF: office letterhead, RTL tables with page breaks, signatures, QR fingerprint. */
object PdfWriter {
    private val NAVY = 0xFF14228F.toInt()
    private val NAVY_DARK = 0xFF0F196E.toInt()
    private val GREY = 0xFF9A9BAD.toInt()
    private val ZEBRA = 0xFFF2F3F9.toInt()
    private const val M = 28f

    private class Ctx(val doc: PdfDocument, val w: Int, val h: Int) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var pageNo = 0
    }

    fun qrBitmap(text: String, size: Int = 300): Bitmap {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        isFakeBoldText = bold
    }

    private fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(10))
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    fun write(context: Context, d: ReportDoc, hash: String, exportedText: String, out: File) {
        val (w, h) = if (d.landscape) 842 to 595 else 595 to 842
        val pdf = PdfDocument()
        val cx = Ctx(pdf, w, h)
        val letterhead = BitmapFactory.decodeResource(context.resources, R.drawable.letterhead)
        val contentW = w - 2 * M

        fun footer() {
            val c = cx.canvas ?: return
            val p = paint(7.5f, GREY)
            c.drawLine(M, h - 24f, w - M, h - 24f, Paint().apply { color = GREY; strokeWidth = 0.6f })
            drawLayout(c, layout("صفحة ${cx.pageNo}  •  تاريخ ووقت التصدير: $exportedText  •  البصمة: ${hash.take(16)}…", p, contentW, Layout.Alignment.ALIGN_CENTER), M, h - 20f)
        }

        fun newPage() {
            cx.page?.let { footer(); pdf.finishPage(it) }
            cx.pageNo++
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, cx.pageNo).create())
            cx.page = page
            cx.canvas = page.canvas
            val c = page.canvas
            // letterhead banner
            val bw = contentW
            val bh = bw * letterhead.height / letterhead.width
            val maxH = if (d.landscape) 62f else 78f
            val scale = minOf(1f, maxH / bh)
            val dw = bw * scale; val dh = bh * scale
            c.drawBitmap(letterhead, null, RectF(M + (contentW - dw) / 2, M - 10f, M + (contentW - dw) / 2 + dw, M - 10f + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            cx.y = M - 10f + dh + 4f
        }

        fun ensure(space: Float): Boolean {
            if (cx.y + space > h - 32f) { newPage(); return true }
            return false
        }

        newPage()
        val c0 = cx.canvas!!
        // title band
        val titleP = paint(13f, Color.WHITE, true)
        val title = "${d.title}  —  نموذج رقم (${d.formNo})"
        val tl = layout(title, titleP, contentW - 12, Layout.Alignment.ALIGN_CENTER)
        c0.drawRoundRect(RectF(M, cx.y, w - M, cx.y + tl.height + 10), 4f, 4f, Paint().apply { color = NAVY })
        drawLayout(c0, tl, M + 6, cx.y + 5)
        cx.y += tl.height + 16

        // header info in two/three columns
        val info = d.headerLines()
        val perRow = if (d.landscape) 3 else 2
        val cellW = contentW / perRow
        val infoP = paint(8.5f)
        info.chunked(perRow).forEach { rowItems ->
            var rowH = 0f
            val layouts = rowItems.map { (k, v) -> layout("$k: $v", infoP, cellW - 6, Layout.Alignment.ALIGN_NORMAL) }
            layouts.forEach { rowH = maxOf(rowH, it.height.toFloat()) }
            ensure(rowH + 4)
            layouts.forEachIndexed { i, l ->
                // RTL: first item on the right
                val x = w - M - (i + 1) * cellW + 3
                drawLayout(cx.canvas!!, l, x, cx.y)
            }
            cx.y += rowH + 4
        }
        cx.y += 4

        val border = Paint().apply { color = GREY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val headFill = Paint().apply { color = NAVY_DARK }
        val zebra = Paint().apply { color = ZEBRA }
        val totalFill = Paint().apply { color = 0xFFDDE1F7.toInt() }
        val headP = paint(8f, Color.WHITE, true)
        val cellP = paint(8f)
        val totP = paint(8.2f, NAVY_DARK, true)

        for (sec in d.sections) {
            val weights = sec.columns.map { it.weight }
            val sum = weights.sum()
            val widths = weights.map { contentW * it / sum }
            // x positions from the right edge (RTL)
            val xs = FloatArray(widths.size)
            var right = w - M
            widths.forEachIndexed { i, cw -> xs[i] = right - cw; right -= cw }

            fun rowLayouts(cells: List<String>, p: TextPaint) =
                sec.columns.indices.map { i -> layout(cells.getOrElse(i) { "" }, p, widths[i] - 6, Layout.Alignment.ALIGN_CENTER) }

            fun drawRow(cells: List<String>, p: TextPaint, fill: Paint?) {
                val ls = rowLayouts(cells, p)
                val rh = (ls.maxOfOrNull { it.height } ?: 10) + 8f
                val c = cx.canvas!!
                if (fill != null) c.drawRect(M, cx.y, w - M, cx.y + rh, fill)
                ls.forEachIndexed { i, l ->
                    c.drawRect(xs[i], cx.y, xs[i] + widths[i], cx.y + rh, border)
                    drawLayout(c, l, xs[i] + 3, cx.y + (rh - l.height) / 2)
                }
                cx.y += rh
            }

            fun header() = drawRow(sec.columns.map { it.title }, headP, headFill)

            if (sec.title != null) {
                ensure(60f)
                val l = layout(sec.title, paint(10f, NAVY, true), contentW, Layout.Alignment.ALIGN_NORMAL)
                drawLayout(cx.canvas!!, l, M, cx.y)
                cx.y += l.height + 4
            }
            ensure(40f)
            header()
            if (sec.rows.isEmpty()) {
                drawRow(listOf("لا توجد حركة في هذه الفترة") + List(sec.columns.size - 1) { "" }, cellP, null)
            }
            sec.rows.forEachIndexed { idx, r ->
                val est = (rowLayouts(r, cellP).maxOfOrNull { it.height } ?: 10) + 8f
                if (ensure(est)) header()
                drawRow(r, cellP, if (idx % 2 == 1) zebra else null)
            }
            sec.totals.forEach { t ->
                if (ensure(20f)) header()
                drawRow(t, totP, totalFill)
            }
            if (sec.notes.isNotEmpty()) {
                cx.y += 4
                val np = paint(8.5f, NAVY_DARK, true)
                sec.notes.chunked(perRow).forEach { items ->
                    val ls = items.map { (k, v) -> layout("$k: $v", np, cellW - 6, Layout.Alignment.ALIGN_NORMAL) }
                    val rh = (ls.maxOfOrNull { it.height } ?: 10) + 3f
                    ensure(rh)
                    ls.forEachIndexed { i, l -> drawLayout(cx.canvas!!, l, w - M - (i + 1) * cellW + 3, cx.y) }
                    cx.y += rh
                }
            }
            cx.y += 10
        }

        // signatures + QR
        val qrSize = 78f
        ensure(qrSize + 20)
        val c = cx.canvas!!
        val sigW = (contentW - qrSize - 10) / d.signatures.size
        d.signatures.forEachIndexed { i, (role, name) ->
            val x = w - M - (i + 1) * sigW
            val l = layout("$role\nالاسم: $name\nالتوقيع: ..................", paint(8.5f, Color.BLACK, false), sigW - 8, Layout.Alignment.ALIGN_CENTER)
            drawLayout(c, l, x + 4, cx.y + 6)
        }
        val qr = qrBitmap(d.qrText(hash, exportedText))
        c.drawBitmap(qr, null, RectF(M, cx.y, M + qrSize, cx.y + qrSize), Paint())
        footer()
        pdf.finishPage(cx.page!!)
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }
}
