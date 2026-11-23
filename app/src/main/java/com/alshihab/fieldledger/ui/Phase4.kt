package com.alshihab.fieldledger.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.AppLock
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.Attachment
import com.alshihab.fieldledger.data.AssignmentTotal
import com.alshihab.fieldledger.data.AssignmentWithWorker
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.FxRate
import com.alshihab.fieldledger.data.PayType
import com.alshihab.fieldledger.data.Repo
import com.alshihab.fieldledger.report.Exporter
import com.alshihab.fieldledger.security.AttachmentStore
import com.alshihab.fieldledger.security.Backup
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Fx
import com.alshihab.fieldledger.util.Money
import com.alshihab.fieldledger.util.fmtRate
import com.alshihab.fieldledger.util.parseDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

const val SETTLE_ENTITY = "settle"

// =====================================================================
// Signature pad
// =====================================================================
@Composable
fun SignatureDialog(title: String, onDone: (ByteArray?) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column {
                Text("وقّع بإصبعك داخل المربع", style = MaterialTheme.typography.bodySmall)
                ComposeCanvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White)
                        .border(1.dp, BrandGray)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { change, _ -> current = current + change.position },
                                onDragEnd = { strokes.add(current); current = emptyList() }
                            )
                        }
                ) {
                    (strokes + listOf(current)).forEach { pts ->
                        for (i in 1 until pts.size) drawLine(Color(0xFF0F196E), pts[i - 1], pts[i], strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                }
                TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("مسح") }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(android.graphics.Color.WHITE)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0F196E.toInt(); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
                }
                strokes.forEach { pts -> for (i in 1 until pts.size) c.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, p) }
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                onDone(out.toByteArray())
            }) { Text("اعتماد التوقيع") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("إلغاء") } }
    )
}


// =====================================================================
// Settlement (تصفية حساب العامل / العمال)
// =====================================================================
private data class SettleRow(
    val selected: Boolean,
    val payCurrency: String,
    val rate: String,
    val payLeft: Boolean,
    val signature: ByteArray? = null
)

@Composable
fun SettlementScreen(nav: NavController, projectId: Long, onlyAssignment: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val today = remember { Dates.today() }
    val assignments by rememberFlow(projectId, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(projectId) }
    val totals by rememberFlow(projectId, "tot", initial = emptyList<AssignmentTotal>()) { repo.dao.assignmentTotals(projectId) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val fx by rememberFlow(Unit, "fx", initial = emptyList<FxRate>()) { repo.dao.fxRates() }
    val state = remember { mutableStateMapOf<Long, SettleRow>() }
    var deactivate by remember { mutableStateOf(onlyAssignment != 0L) }
    var note by remember { mutableStateOf("تصفية نهائية لكامل المستحقات") }
    var signingFor by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tMap = totals.associateBy { it.assignmentId }

    fun remaining(aw: AssignmentWithWorker): Long {
        val t = tMap[aw.a.id]
        val w = t?.withdrawn ?: 0L
        return if (aw.a.payType == PayType.CONTRACT) aw.a.contractAmount - w else (t?.due ?: 0L) - w
    }

    val list = assignments.filter { if (onlyAssignment != 0L) it.a.id == onlyAssignment else remaining(it) != 0L }
    fun rowOf(aw: AssignmentWithWorker) = state[aw.a.id] ?: SettleRow(onlyAssignment != 0L && remaining(aw) > 0, aw.a.currency, "", true)

    fun factor(aw: AssignmentWithWorker, r: SettleRow): Double? {
        if (r.payCurrency == aw.a.currency) return 1.0
        val v = parseDecimal(r.rate) ?: return null
        if (v <= 0) return null
        return if (r.payLeft) v else 1.0 / v
    }

    fun payAmount(aw: AssignmentWithWorker, r: SettleRow): Long? {
        val f = factor(aw, r) ?: return null
        return Math.round(remaining(aw) / f)
    }

    Scaffold(topBar = { AppBar(if (onlyAssignment != 0L) "تصفية حساب عامل" else "تصفية حسابات العمال", onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "تُحسب التصفية بعملة اتفاق كل عامل: المتبقي = المستحق − المسحوب (أو مبلغ المقاولة − المدفوع). " +
                        "يمكن الدفع بعملة أخرى بسعر صرف اليوم، ويُسجَّل المبلغ في الصندوق ويُصفَّر حساب العامل.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (list.isEmpty()) item { EmptyState("لا توجد حسابات تحتاج تصفية") }
            items(list, key = { it.a.id }) { aw ->
                val r = rowOf(aw)
                val rem = remaining(aw)
                val pay = payAmount(aw, r)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.selected, enabled = rem > 0, onCheckedChange = { state[aw.a.id] = r.copy(selected = it) })
                        Column(Modifier.weight(1f)) {
                            Text("${aw.a.cardNo}. ${aw.worker.name}", fontWeight = FontWeight.Bold)
                            Text("${aw.worker.profession} • ${payLabel(aw.a, currencies)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(rem, aw.a.currency, currencies), fontWeight = FontWeight.Bold, color = if (rem < 0) Bad else Brand)
                    }
                    if (rem < 0) Text("المسحوب أكبر من المستحق: المبلغ على العامل ولا يُصرف له شيء", color = Bad, style = MaterialTheme.typography.bodySmall)
                    if (r.selected && rem > 0) {
                        DropdownField(
                            "عملة الصرف", currencies, currencies.firstOrNull { it.code == r.payCurrency }, { it.code },
                            { c ->
                                val left = Fx.aOnLeft(fx, c.code, aw.a.currency) { x -> decimalsOf(x, currencies) }
                                val f = Fx.factor(fx, c.code, aw.a.currency, today)
                                state[aw.a.id] = r.copy(
                                    payCurrency = c.code, payLeft = left,
                                    rate = if (c.code == aw.a.currency) "" else f?.let { fmtRate(if (left) it else 1.0 / it) } ?: ""
                                )
                            }
                        )
                        if (r.payCurrency != aw.a.currency) Field(
                            if (r.payLeft) "سعر الصرف: 1 ${r.payCurrency} = ؟ ${aw.a.currency}" else "سعر الصرف: 1 ${aw.a.currency} = ؟ ${r.payCurrency}",
                            r.rate, { state[aw.a.id] = r.copy(rate = it) }, keyboard = KeyboardType.Decimal
                        )
                        Text(
                            if (pay != null) "يُصرف للعامل: ${money(pay, r.payCurrency, currencies)}" else "أدخل سعر الصرف",
                            fontWeight = FontWeight.Bold, color = if (pay != null) Good else Bad
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { signingFor = aw.a.id }) { Text(if (r.signature != null) "✓ تم التوقيع - إعادة" else "توقيع العامل بالاستلام") }
                        }
                    }
                }
            }
            item {
                SectionCard {
                    Field("البيان", note, { note = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("إنهاء عمل العامل في المشروع بعد التصفية", modifier = Modifier.weight(1f))
                        Switch(checked = deactivate, onCheckedChange = { deactivate = it })
                    }
                    error?.let { Text(it, color = Bad) }
                    val chosen = list.filter { rowOf(it).selected && remaining(it) > 0 }
                    val sums = chosen.groupBy { rowOf(it).payCurrency }.mapValues { e -> e.value.sumOf { payAmount(it, rowOf(it)) ?: 0L } }
                    sums.forEach { (c, v) -> KeyValue("إجمالي الصرف ($c)", money(v, c, currencies), Bad, bold = true) }
                    Button(
                        enabled = chosen.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = {
                            val bad = chosen.firstOrNull { payAmount(it, rowOf(it)) == null }
                            if (bad != null) { error = "أدخل سعر الصرف للعامل ${bad.worker.name}"; return@Button }
                            busy = true
                            scope.launch {
                                try {
                                    val items = chosen.map { aw ->
                                        val r = rowOf(aw)
                                        Repo.SettleItem(aw.a.id, remaining(aw), r.payCurrency, payAmount(aw, r) ?: 0L, factor(aw, r) ?: 1.0, note.trim(), deactivate)
                                    }
                                    val ids = repo.settleWorkers(projectId, items)
                                    // store signatures as encrypted attachments of the settlement line
                                    chosen.forEachIndexed { i, aw ->
                                        val sig = rowOf(aw).signature ?: return@forEachIndexed
                                        withContext(Dispatchers.IO) {
                                            val name = AttachmentStore.newStoredName()
                                            AttachmentStore.storeBytes(ctx, name, sig)
                                            repo.addAttachment(
                                                Attachment(
                                                    projectId = projectId, entity = SETTLE_ENTITY, entityId = ids[i],
                                                    fileName = "توقيع ${aw.worker.name}.png", mime = "image/png", size = sig.size.toLong(),
                                                    sha256 = AttachmentStore.sha256(sig), storedName = name
                                                )
                                            )
                                        }
                                    }
                                    Toast.makeText(ctx, "تمت التصفية", Toast.LENGTH_SHORT).show()
                                    nav.popBackStack()
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) "جارٍ التصفية…" else "تنفيذ التصفية (${chosen.size})") }
                }
            }
        }
    }

    signingFor?.let { id ->
        SignatureDialog("توقيع العامل باستلام مستحقاته") { bytes ->
            if (bytes != null) {
                val base = state[id] ?: assignments.firstOrNull { it.a.id == id }?.let { aw -> rowOf(aw) }
                if (base != null) state[id] = base.copy(signature = bytes)
            }
            signingFor = null
        }
    }
}

// =====================================================================
// Worker correction (for locked days)
// =====================================================================
@Composable
fun WorkerCorrectionDialog(projectId: Long, assignmentId: Long, currency: String, currencies: List<CurrencyEntity>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dueSign by remember { mutableStateOf("+") }
    var due by remember { mutableStateOf("") }
    var wSign by remember { mutableStateOf("+") }
    var wd by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("قيد تصحيحي لحساب العامل") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("للأيام المقفلة: أدخل الفرق فقط (زيادة أو نقص) بعملة حساب العامل. يُسجَّل اليوم ويبقى الكشف الأصلي كما هو.", style = MaterialTheme.typography.bodySmall)
                Text("المستحق", fontWeight = FontWeight.Bold)
                Choice(listOf("+" to "زيادة", "-" to "نقص"), dueSign, { dueSign = it })
                MoneyField("فرق المستحق", due, { due = it }, currency, currencies, showWords = false)
                Text("المسحوب", fontWeight = FontWeight.Bold)
                Choice(listOf("+" to "زيادة (صرف من الصندوق)", "-" to "نقص (إرجاع للصندوق)"), wSign, { wSign = it })
                MoneyField("فرق المسحوب", wd, { wd = it }, currency, currencies, showWords = false)
                Field("سبب التصحيح *", reason, { reason = it; error = null }, singleLine = false)
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = (Money.parse(due) ?: 0L) * (if (dueSign == "-") -1 else 1)
                val w = (Money.parse(wd) ?: 0L) * (if (wSign == "-") -1 else 1)
                if (reason.isBlank()) { error = "اكتب سبب التصحيح"; return@TextButton }
                if (d == 0L && w == 0L) { error = "أدخل مبلغ التصحيح"; return@TextButton }
                scope.launch {
                    Graph.repo.addWorkerCorrection(projectId, assignmentId, d, w, reason.trim())
                    Toast.makeText(ctx, "تم تسجيل القيد التصحيحي", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("إلغاء") } }
    )
}

// =====================================================================
// Integrity check (hash chain)
// =====================================================================
@Composable
fun IntegrityScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    var result by remember { mutableStateOf<Repo.SealReport?>(null) }
    var sealedNow by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        sealedNow = withContext(Dispatchers.IO) { repo.sealLockedSheets() }
        result = withContext(Dispatchers.IO) { repo.verifySeals() }
        busy = false
    }
    Scaffold(topBar = { AppBar("التحقق من سلامة الكشوفات", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "كل كشف يُختم ببصمة SHA-256 بعد قفله، وكل بصمة مرتبطة بالتي قبلها. أي تعديل على كشف مقفل من خارج التطبيق يكسر السلسلة ويظهر هنا.",
                style = MaterialTheme.typography.bodySmall
            )
            if (busy) Text("جارٍ التحقق…")
            result?.let { r ->
                SectionCard {
                    KeyValue("الكشوفات المختومة", "${r.total}")
                    if (sealedNow > 0) KeyValue("خُتم الآن", "$sealedNow")
                    KeyValue("النتيجة", if (r.broken.isEmpty()) "سليمة ✓" else "${r.broken.size} مشكلة", if (r.broken.isEmpty()) Good else Bad, bold = true)
                }
                r.broken.forEach { (sh, msg) ->
                    Banner("${sh?.let { "كشف ${it.form} رقم ${it.serial} بتاريخ ${it.date}: " } ?: ""}$msg", Bad)
                }
            }
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    sealedNow = withContext(Dispatchers.IO) { repo.sealLockedSheets() }
                    result = withContext(Dispatchers.IO) { repo.verifySeals() }
                    busy = false
                }
            }, enabled = !busy) { Text("إعادة التحقق") }
        }
    }
}

// =====================================================================
// Backup / restore
// =====================================================================
@Composable
fun BackupScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var rpw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { Backup.restore(ctx, uri, rpw) }
                Graph.repo.logEvent("استعادة", "استعادة نسخة احتياطية: $n سجل")
                msg = "تمت الاستعادة ($n سجل). سيُغلق التطبيق الآن، افتحه من جديد."
                kotlinx.coroutines.delay(2500)
                Runtime.getRuntime().exit(0)
            } catch (e: IllegalStateException) {
                msg = e.message
            } catch (e: Exception) {
                msg = "تعذرت الاستعادة: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { AppBar("النسخ الاحتياطي والاستعادة", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard("إنشاء نسخة احتياطية") {
                Text(
                    "ملف واحد مشفّر بكلمة مرور تختارها، يحتوي كل المشاريع والعمال والكشوفات والمرفقات. احفظه خارج الجوال (بريد، فلاشة، كمبيوتر المكتب). بدون كلمة المرور لا يمكن فتحه.",
                    style = MaterialTheme.typography.bodySmall
                )
                PwField("كلمة مرور النسخة (8 أحرف على الأقل)", pw1) { pw1 = it }
                PwField("تأكيد كلمة المرور", pw2) { pw2 = it }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (pw1.length < 8) { msg = "كلمة المرور قصيرة"; return@Button }
                    if (pw1 != pw2) { msg = "كلمتا المرور غير متطابقتين"; return@Button }
                    busy = true; msg = null
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { Backup.create(ctx, pw1) }
                            Graph.repo.logEvent("نسخ احتياطي", "${f.name} (${f.length() / 1024} KB)")
                            ready = f
                        } catch (e: Exception) {
                            msg = "تعذر إنشاء النسخة: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "جارٍ التجهيز…" else "إنشاء النسخة ومشاركتها") }
            }
            SectionCard("استعادة نسخة احتياطية") {
                Text(
                    "تتم الاستعادة فقط على دفتر فارغ (جوال جديد أو بعد إعادة التثبيت) حتى لا تُستبدل أي بيانات موجودة.",
                    style = MaterialTheme.typography.bodySmall
                )
                PwField("كلمة مرور النسخة", rpw) { rpw = it }
                OutlinedButton(enabled = !busy && rpw.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    AppLock.allowExternal()
                    picker.launch(arrayOf("*/*"))
                }) { Text("اختيار ملف النسخة (.slb)") }
            }
            msg?.let { Banner(it, Brand) }
        }
    }

    ready?.let { f ->
        AlertDialog(
            onDismissRequest = { ready = null },
            title = { Text("النسخة جاهزة") },
            text = { Text("${f.name}\n${f.length() / 1024} KB") },
            confirmButton = {
                TextButton(onClick = {
                    ready = null
                    AppLock.allowExternal()
                    ctx.startActivity(Exporter.shareIntent(ctx, f))
                }) { Text("مشاركة / حفظ") }
            },
            dismissButton = { TextButton(onClick = { ready = null }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun PwField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}
