package com.alshihab.fieldledger.report

import android.content.Context
import android.content.Intent
import com.alshihab.fieldledger.BuildConfig
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.security.AttachmentStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {
    const val PDF = "pdf"
    const val XLSX = "xlsx"

    /** Builds the report, writes the file into the private cache and records the export (with its fingerprint) in the audit log. */
    suspend fun export(ctx: Context, kind: ReportKind, projectId: Long, from: String, to: String, extraId: Long, format: String): File {
        val doc = builder(ctx).build(kind, projectId, from, to, extraId)
        val hash = doc.fingerprint()
        val exportedText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(doc.exportedAt))
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val safeProject = doc.project.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(doc.exportedAt))
        val safeTitle = doc.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val file = File(dir, "${safeTitle}_${safeProject}_$stamp.$format")
        if (format == PDF) PdfWriter.write(ctx, doc, hash, exportedText, file) else XlsxWriter.write(doc, hash, exportedText, file)
        Graph.repo.logEvent("تصدير", "${doc.title} - ${doc.period} - ${format.uppercase()} - SHA256:$hash")
        return file
    }

    private fun builder(ctx: Context) = ReportBuilder(Graph.repo.dao, BuildConfig.VERSION_NAME) { stored, sha ->
        try { AttachmentStore.decrypt(ctx, stored, sha) } catch (e: Exception) { null }
    }

    /** Every report for the period in ONE file; the PDF ends with all attached pictures / PDFs of that period. */
    suspend fun exportFull(ctx: Context, projectId: Long, from: String, to: String, format: String): File {
        val dao = Graph.repo.dao
        val b = builder(ctx)
        val kinds = ReportKind.values().filter { it != ReportKind.STATEMENT }
        val docs = kinds.map { b.build(it, projectId, from, to) }
        val p = docs.first().project
        val now = System.currentTimeMillis()
        val exportedText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val period = docs.first().period

        // attachments of entries dated inside the period
        val cur = dao.currenciesList()
        fun dec(c: String) = cur.firstOrNull { it.code == c }?.decimals ?: 2
        val appendix = mutableListOf<Pair<String, Appendix>>()
        val listRows = mutableListOf<List<String>>()
        for (a in dao.attachmentsForProject(projectId)) {
            val (date, label) = when (a.entity) {
                "cash" -> dao.cashEntry(a.entityId)?.let { e ->
                    e.date to "قيد ${if (e.kind == "IN") "قبض" else "صرف"}: ${e.category} ${com.alshihab.fieldledger.util.Money.fmt(e.amount, dec(e.currency))} ${e.currency}"
                }
                "move" -> dao.move(a.entityId)?.let { m ->
                    val mat = dao.material(m.materialId)
                    m.date to "${if (m.type == "P") "شراء" else "توريد"}: ${mat?.name ?: ""} ${com.alshihab.fieldledger.util.fmtNum(m.qty)} ${mat?.unit ?: ""}"
                }
                else -> dao.attendanceById(a.entityId)?.let { at -> at.date to "تصفية حساب عامل" }
            } ?: continue
            if (date < from || date > to) continue
            listRows += listOf("${listRows.size + 1}", date, label, a.fileName)
            if (format == PDF) {
                val bytes = try { AttachmentStore.decrypt(ctx, a.storedName, a.sha256) } catch (e: Exception) { null } ?: continue
                appendix += date to Appendix("مرفق ${listRows.size}: $label - $date - ${a.fileName}", a.mime, bytes)
            }
        }
        val listDoc = ReportDoc(
            "ATT", "قائمة المرفقات", p, period,
            listOf(Section(null, listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("القيد", 3f), Col("الملف", 2f)), listRows)),
            exportedAt = now, appVersion = BuildConfig.VERSION_NAME
        )
        val all = docs.map { it.copy(exportedAt = now) } + listDoc

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val safeProject = p.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        val file = File(dir, "التقرير_الشامل_${safeProject}_$stamp.$format")
        val combined = java.security.MessageDigest.getInstance("SHA-256")
            .digest(all.joinToString("|") { it.fingerprint() }.toByteArray()).joinToString("") { "%02x".format(it) }
        if (format == PDF) {
            val pdf = android.graphics.pdf.PdfDocument()
            val s = PdfWriter.Session(ctx, pdf)
            PdfWriter.cover(
                s, "التقرير الشامل للمشروع",
                listOf("المشروع: ${p.name}", "الفترة: $period", "") + all.mapIndexed { i, d -> "${i + 1}. ${d.title}" } +
                    listOf("", "المرفقات: ${appendix.size} ملف في نهاية التقرير"),
                exportedText
            )
            all.forEach { d -> PdfWriter.render(s, d, d.fingerprint(), exportedText) }
            appendix.sortedBy { it.first }.forEach { PdfWriter.appendixPage(s, it.second, exportedText) }
            file.outputStream().use { pdf.writeTo(it) }
            pdf.close()
        } else {
            XlsxWriter.writeBook(all, exportedText, file)
        }
        Graph.repo.logEvent("تصدير", "التقرير الشامل - $period - ${format.uppercase()} - ${appendix.size} مرفق - SHA256:$combined")
        return file
    }

    fun shareIntent(ctx: Context, file: File): Intent {
        val mime = if (file.name.endsWith(".pdf")) "application/pdf"
        else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, AttachmentStore.uriFor(ctx, file))
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "مشاركة / حفظ الكشف")
    }

    fun viewIntent(ctx: Context, file: File): Intent {
        val mime = if (file.name.endsWith(".pdf")) "application/pdf"
        else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(AttachmentStore.uriFor(ctx, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
