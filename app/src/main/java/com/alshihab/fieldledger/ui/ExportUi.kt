package com.alshihab.fieldledger.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alshihab.fieldledger.AppLock
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.report.Exporter
import com.alshihab.fieldledger.report.ReportKind
import com.alshihab.fieldledger.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** PDF / Excel export buttons. After exporting the user can open the file or share/save it. */
@Composable
fun ExportButtons(projectId: Long, kind: ReportKind, from: String, to: String, extraId: Long = 0, tint: Color = Color.White) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<File?>(null) }

    fun run(format: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                ready = withContext(Dispatchers.IO) { Exporter.export(ctx, kind, projectId, from, to, extraId, format) }
            } catch (e: Exception) {
                Toast.makeText(ctx, "تعذر التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), color = tint, strokeWidth = 2.dp)
        IconButton(onClick = { run(Exporter.PDF) }, enabled = !busy) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = "تصدير PDF", tint = tint)
        }
        IconButton(onClick = { run(Exporter.XLSX) }, enabled = !busy) {
            Icon(Icons.Filled.TableView, contentDescription = "تصدير Excel", tint = tint)
        }
    }

    ready?.let { f ->
        AlertDialog(
            onDismissRequest = { ready = null },
            title = { Text("تم تجهيز الكشف") },
            text = { Text("${f.name}\n\nيحمل الملف بيانات المشروع ووقت التصدير وبصمة تحقق (QR).") },
            confirmButton = {
                TextButton(onClick = {
                    ready = null
                    AppLock.allowExternal()
                    try { ctx.startActivity(Exporter.viewIntent(ctx, f)) } catch (e: Exception) {
                        Toast.makeText(ctx, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_LONG).show()
                    }
                }) { Text("فتح") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ready = null
                    AppLock.allowExternal()
                    ctx.startActivity(Exporter.shareIntent(ctx, f))
                }) { Text("مشاركة / حفظ") }
            }
        )
    }
}

/** Report centre inside the project: choose a period, then export any form as PDF or Excel. */
@Composable
fun ReportsExportCard(p: Project) {
    var from by rememberSaveable { mutableStateOf(Dates.today()) }
    var to by rememberSaveable { mutableStateOf(Dates.today()) }
    var custom by remember { mutableStateOf(false) }

    SectionCard("الكشوفات والتصدير (PDF / Excel)") {
        val today = Dates.today()
        val ws = Dates.weekStart(today)
        val monthStart = today.substring(0, 8) + "01"
        Choice(
            listOf("D" to "اليوم", "W" to "هذا الأسبوع", "PW" to "الأسبوع السابق", "M" to "هذا الشهر", "ALL" to "منذ البداية", "C" to "فترة محددة"),
            when {
                custom -> "C"
                from == today && to == today -> "D"
                from == ws && to == Dates.shift(ws, 6) -> "W"
                from == Dates.shift(ws, -7) && to == Dates.shift(ws, -1) -> "PW"
                from == monthStart && to == today -> "M"
                from == "2000-01-01" -> "ALL"
                else -> "C"
            },
            { k ->
                custom = k == "C"
                when (k) {
                    "D" -> { from = today; to = today }
                    "W" -> { from = ws; to = Dates.shift(ws, 6) }
                    "PW" -> { from = Dates.shift(ws, -7); to = Dates.shift(ws, -1) }
                    "M" -> { from = monthStart; to = today }
                    "ALL" -> { from = "2000-01-01"; to = today }
                    else -> {}
                }
            }
        )
        if (custom) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(from, { from = it }, label = { Text("من yyyy-mm-dd") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(to, { to = it }, label = { Text("إلى") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        val valid = runCatching { java.time.LocalDate.parse(from); java.time.LocalDate.parse(to) }.isSuccess && from <= to
        Text(
            if (valid) (if (from == to) Dates.label(from) else "من $from إلى $to") else "الفترة غير صحيحة",
            style = MaterialTheme.typography.bodySmall, color = if (valid) Brand else Bad
        )
        HorizontalDivider()
        ReportKind.values().filter { it != ReportKind.STATEMENT }.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text("نموذج ${k.formNo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (valid) ExportButtons(p.id, k, from, to, tint = Brand)
            }
            HorizontalDivider()
        }
        Text("كشف حساب أي عامل يُصدَّر من صفحة العامل.", style = MaterialTheme.typography.bodySmall)
    }
    @Suppress("UNUSED_VARIABLE") val w = Modifier.width(1.dp)
}
