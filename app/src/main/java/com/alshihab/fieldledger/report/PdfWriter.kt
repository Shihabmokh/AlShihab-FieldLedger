package com.alshihab.fieldledger.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.alshihab.fieldledger.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** Renders [ReportDoc]s to PDF: office letterhead, RTL tables with page breaks, signatures, QR fingerprint, appendix. */
object PdfWriter {
    private val NAVY = 0xFF14228F.toInt()
    private val NAVY_DARK = 0xFF0F196E.toInt()
    private val GREY = 0xFF9A9BAD.toInt()
    private val ZEBRA = 0xFFF2F3F9.toInt()
    private const val M = 28f

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

    /** Page writer shared by every document appended to one PDF. */
    class Session(val context: Context, val pdf: PdfDocument) {
        var pageNo = 0
        val letterhead: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.letterhead)
    }

    fun write(context: Context, d: ReportDoc, hash: String, exportedText: String, out: File) {
        val pdf = PdfDocument()
        val s = Session(context, pdf)
        render(s, d, hash, exportedText)
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    /** Cover page with the list of included reports (used by the full export). */
    fun cover(s: Session, title: String, lines: List<String>, exportedText: String) {
        s.pageNo++
        val w = 595
        val h = 842
        val page = s.pdf.startPage(PdfDocument.PageInfo.Builder(w, h, s.pageNo).create())
        val c = page.canvas
        val contentW = w - 2 * M
        val bh = contentW * s.letterhead.height / s.letterhead.width
        c.drawBitmap(s.letterhead, null, RectF(M, M, M + contentW, M + bh), Paint(Paint.FILTER_BITMAP_FLAG))
        var y = M + bh + 40
        val t = layout(title, paint(20f, NAVY, true), contentW, Layout.Alignment.ALIGN_CENTER)
        drawLayout(c, t, M, y)
        y += t.height + 30
        lines.forEach { line ->
            val l = layout(line, paint(11f), contentW, Layout.Alignment.ALIGN_NORMAL)
            drawLayout(c, l, M, y)
            y += l.height + 8
        }
        val f = layout("تاريخ ووقت التصدير: $exportedText", paint(9f, GREY), contentW, Layout.Alignment.ALIGN_CENTER)
        drawLayout(c, f, M, h - 40f)
        s.pdf.finishPage(page)
    }

    fun render(s: Session, d: ReportDoc, hash: String, exportedText: String) {
        val w = if (d.landscape) 842 else 595
        val h = if (d.landscape) 595 else 842
        val pdf = s.pdf
        val contentW = w - 2 * M
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun footer() {
            val c = canvas ?: return
            val p = paint(7.5f, GREY)
            c.drawLine(M, h - 24f, w - M, h - 24f, Paint().apply { color = GREY; strokeWidth = 0.6f })
            drawLayout(c, layout("صفحة ${s.pageNo}  •  ${d.title}  •  تاريخ ووقت التصدير: $exportedText", p, contentW, Layout.Alignment.ALIGN_CENTER), M, h - 20f)
        }

        fun newPage() {
            val old = page
            if (old != null) { footer(); pdf.finishPage(old) }
            s.pageNo++
            val pg = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, s.pageNo).create())
            page = pg
            canvas = pg.canvas
            val c = pg.canvas
            val bh = contentW * s.letterhead.height / s.letterhead.width
            val maxH = if (d.landscape) 62f else 78f
            val scale = minOf(1f, maxH / bh)
            val dw = contentW * scale
            val dh = bh * scale
            c.drawBitmap(s.letterhead, null, RectF(M + (contentW - dw) / 2, M - 10f, M + (contentW - dw) / 2 + dw, M - 10f + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            y = M - 10f + dh + 4f
        }

        fun ensure(space: Float): Boolean {
            if (y + space > h - 32f) { newPage(); return true }
            return false
        }

        newPage()
        val tl = layout(d.title, paint(13f, Color.WHITE, true), contentW - 12, Layout.Alignment.ALIGN_CENTER)
        canvas!!.drawRoundRect(RectF(M, y, w - M, y + tl.height + 10), 4f, 4f, Paint().apply { color = NAVY })
        drawLayout(canvas!!, tl, M + 6, y + 5)
        y += tl.height + 16

        val perRow = if (d.landscape) 3 else 2
        val cellW = contentW / perRow
        val infoP = paint(8.5f)
        d.headerLines().chunked(perRow).forEach { rowItems ->
            val layouts = rowItems.map { (k, v) -> layout("$k: $v", infoP, cellW - 6, Layout.Alignment.ALIGN_NORMAL) }
            val rowH = layouts.maxOf { it.height }.toFloat()
            ensure(rowH + 4)
            layouts.forEachIndexed { i, l -> drawLayout(canvas!!, l, w - M - (i + 1) * cellW + 3, y) }
            y += rowH + 4
        }
        y += 4

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
            val xs = FloatArray(widths.size)
            var right = w - M
            widths.forEachIndexed { i, cw -> xs[i] = right - cw; right -= cw }

            fun rowLayouts(cells: List<String>, p: TextPaint) =
                sec.columns.indices.map { i -> layout(cells.getOrElse(i) { "" }, p, widths[i] - 6, Layout.Alignment.ALIGN_CENTER) }

            fun drawRow(cells: List<String>, p: TextPaint, fill: Paint?) {
                val ls = rowLayouts(cells, p)
                val rh = (ls.maxOfOrNull { it.height } ?: 10) + 8f
                val c = canvas!!
                if (fill != null) c.drawRect(M, y, w - M, y + rh, fill)
                ls.forEachIndexed { i, l ->
                    c.drawRect(xs[i], y, xs[i] + widths[i], y + rh, border)
                    drawLayout(c, l, xs[i] + 3, y + (rh - l.height) / 2)
                }
                y += rh
            }

            fun header() = drawRow(sec.columns.map { it.title }, headP, headFill)

            val st = sec.title
            if (st != null) {
                ensure(60f)
                val l = layout(st, paint(10f, NAVY, true), contentW, Layout.Alignment.ALIGN_NORMAL)
                drawLayout(canvas!!, l, M, y)
                y += l.height + 4
            }
            ensure(40f)
            header()
            if (sec.rows.isEmpty()) drawRow(listOf("لا توجد حركة في هذه الفترة") + List(sec.columns.size - 1) { "" }, cellP, null)
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
                y += 4
                val np = paint(8.5f, NAVY_DARK, true)
                sec.notes.chunked(perRow).forEach { items ->
                    val ls = items.map { (k, v) -> layout("$k: $v", np, cellW - 6, Layout.Alignment.ALIGN_NORMAL) }
                    val rh = ls.maxOf { it.height } + 3f
                    ensure(rh)
                    ls.forEachIndexed { i, l -> drawLayout(canvas!!, l, w - M - (i + 1) * cellW + 3, y) }
                    y += rh
                }
            }
            y += 10
        }

        val decl = d.declaration
        if (decl != null) {
            val l = layout(decl, paint(10f), contentW, Layout.Alignment.ALIGN_NORMAL)
            ensure(l.height + 10f)
            drawLayout(canvas!!, l, M, y)
            y += l.height + 10
        }

        // signatures + QR
        val qrSize = 78f
        ensure(qrSize + 30)
        val c = canvas!!
        val sigW = (contentW - qrSize - 10) / d.signatures.size
        val sigImg = d.signatureImage
        d.signatures.forEachIndexed { i, (role, name) ->
            val x = w - M - (i + 1) * sigW
            val l = layout("$role\nالاسم: $name\nالتوقيع: ..................", paint(8.5f), sigW - 8, Layout.Alignment.ALIGN_CENTER)
            drawLayout(c, l, x + 4, y + 6)
            if (i == 0 && sigImg != null) {
                val bmp = BitmapFactory.decodeByteArray(sigImg, 0, sigImg.size)
                if (bmp != null) {
                    val sh = 34f
                    val sw = minOf(sigW - 10, sh * bmp.width / bmp.height)
                    c.drawBitmap(bmp, null, RectF(x + (sigW - sw) / 2, y + l.height + 4, x + (sigW + sw) / 2, y + l.height + 4 + sh), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
        val qr = qrBitmap(d.qrText(hash, exportedText))
        c.drawBitmap(qr, null, RectF(M, y, M + qrSize, y + qrSize), Paint())
        footer()
        pdf.finishPage(page!!)

        d.appendix.forEach { a -> appendixPage(s, a, exportedText) }
    }

    fun appendixPage(s: Session, a: Appendix, exportedText: String) {
        val bitmaps: List<Bitmap> = try {
            if (a.mime == "application/pdf") renderPdf(s.context, a.bytes)
            else listOfNotNull(BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size))
        } catch (e: Exception) {
            emptyList()
        }
        val w = 595
        val h = 842
        bitmaps.forEachIndexed { idx, bmp ->
            s.pageNo++
            val page = s.pdf.startPage(PdfDocument.PageInfo.Builder(w, h, s.pageNo).create())
            val c = page.canvas
            val title = a.title + if (bitmaps.size > 1) "  (${idx + 1}/${bitmaps.size})" else ""
            val tl = layout(title, paint(10f, NAVY, true), w - 2 * M, Layout.Alignment.ALIGN_NORMAL)
            drawLayout(c, tl, M, M)
            val top = M + tl.height + 8
            val maxW = w - 2 * M
            val maxH = h - top - 40
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            c.drawBitmap(bmp, null, RectF(M + (maxW - dw) / 2, top, M + (maxW - dw) / 2 + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            val f = layout("مرفق • صفحة ${s.pageNo} • $exportedText", paint(7.5f, GREY), w - 2 * M, Layout.Alignment.ALIGN_CENTER)
            drawLayout(c, f, M, h - 20f)
            s.pdf.finishPage(page)
            bmp.recycle()
        }
    }

    private fun renderPdf(context: Context, bytes: ByteArray): List<Bitmap> {
        val tmp = File.createTempFile("att", ".pdf", context.cacheDir)
        tmp.writeBytes(bytes)
        val out = mutableListOf<Bitmap>()
        try {
            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(fd)
            try {
                for (i in 0 until minOf(r.pageCount, 10)) {
                    val pg = r.openPage(i)
                    val scale = 1200f / maxOf(pg.width, pg.height)
                    val bmp = Bitmap.createBitmap((pg.width * scale).toInt(), (pg.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pg.close()
                    out += bmp
                }
            } finally {
                r.close()
                fd.close()
            }
        } finally {
            tmp.delete()
        }
        return out
    }
}
