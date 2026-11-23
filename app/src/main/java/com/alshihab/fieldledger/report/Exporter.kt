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
        val doc = ReportBuilder(Graph.repo.dao, BuildConfig.VERSION_NAME).build(kind, projectId, from, to, extraId)
        val hash = doc.fingerprint()
        val exportedText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(doc.exportedAt))
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val safeProject = doc.project.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(doc.exportedAt))
        val file = File(dir, "${kind.formNo.replace("/", "-")}_${safeProject}_$stamp.$format")
        if (format == PDF) PdfWriter.write(ctx, doc, hash, exportedText, file) else XlsxWriter.write(doc, hash, exportedText, file)
        Graph.repo.logEvent("تصدير", "${kind.formNo} ${doc.title} - ${doc.period} - ${format.uppercase()} - SHA256:$hash")
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
