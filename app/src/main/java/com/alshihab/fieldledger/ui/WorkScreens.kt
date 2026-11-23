package com.alshihab.fieldledger.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.filled.Call
import com.alshihab.fieldledger.AppLock
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.Assignment
import com.alshihab.fieldledger.data.AssignmentWithWorker
import com.alshihab.fieldledger.data.Attendance
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.DuplicateException
import com.alshihab.fieldledger.data.Forms
import com.alshihab.fieldledger.data.LockedException
import com.alshihab.fieldledger.data.PayType
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.data.Sheet
import com.alshihab.fieldledger.data.Status
import com.alshihab.fieldledger.data.Worker
import com.alshihab.fieldledger.data.WorkerCategory
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money
import com.alshihab.fieldledger.util.fmtNum
import com.alshihab.fieldledger.util.parseDecimal
import kotlinx.coroutines.launch

// =====================================================================
// Worker agreement inside a project
// =====================================================================
@Composable
fun AssignmentFormScreen(nav: NavController, projectId: Long, assignmentId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val isNew = assignmentId == 0L
    val workers by rememberFlow(Unit, initial = emptyList<Worker>()) { repo.dao.workers() }
    val assigned by rememberFlow(projectId, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(projectId) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val wageRates by rememberFlow("rates", initial = emptyList<com.alshihab.fieldledger.data.WageRate>()) { repo.dao.wageRates() }
    val manualAllowed = remember { com.alshihab.fieldledger.security.OfficePolicy.allowManualWage(ctx) }
    var rateId by rememberSaveable { mutableStateOf(0L) }
    var wageFromList by rememberSaveable { mutableStateOf(!manualAllowed) }

    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<Assignment?>(null) }
    var workerId by rememberSaveable { mutableStateOf(0L) }
    var category by rememberSaveable { mutableStateOf(WorkerCategory.TEMP) }
    var payType by rememberSaveable { mutableStateOf(PayType.DAILY) }
    var currency by rememberSaveable { mutableStateOf("YER") }
    var wage by rememberSaveable { mutableStateOf("") }
    var otRate by rememberSaveable { mutableStateOf("") }
    var baseHours by rememberSaveable { mutableStateOf("8") }
    var contractDesc by rememberSaveable { mutableStateOf("") }
    var contractAmount by rememberSaveable { mutableStateOf("") }
    var progress by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(assignmentId) {
        if (loaded) return@LaunchedEffect
        if (isNew) {
            repo.dao.project(projectId)?.let { currency = it.baseCurrency }
        } else {
            repo.dao.assignment(assignmentId)?.let { a ->
                original = a
                workerId = a.workerId; category = a.category; payType = a.payType; currency = a.currency
                wage = Money.toInput(a.dailyWage); otRate = Money.toInput(a.overtimeRate)
                baseHours = a.baseHours.toString(); contractDesc = a.contractDesc
                contractAmount = Money.toInput(a.contractAmount)
                progress = if (a.progress == 0) "" else a.progress.toString(); active = a.active
                rateId = a.wageRateId; wageFromList = a.wageRateId != 0L || !manualAllowed
            }
        }
        loaded = true
    }

    val takenIds = assigned.map { it.a.workerId }.toSet()
    val selectable = if (isNew) workers.filter { it.active && it.id !in takenIds } else workers.filter { it.id == workerId }

    Scaffold(topBar = { AppBar(if (isNew) "إضافة عامل للمشروع" else "اتفاق العامل", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard("العامل") {
                DropdownField(
                    "اختر العامل من السجل",
                    selectable,
                    workers.firstOrNull { it.id == workerId },
                    { w -> if (w.profession.isBlank()) w.name else "${w.name} - ${w.profession}" },
                    { workerId = it.id },
                    enabled = isNew
                )
                if (isNew) TextButton(onClick = { nav.navigate(Routes.workerForm(0)) }) {
                    Icon(Icons.Filled.PersonAdd, null); Text("  عامل غير موجود؟ أضفه للسجل أولاً")
                }
                Text("تصنيف العامل (للكشوفات 24T / 25T)", style = MaterialTheme.typography.bodySmall)
                Choice(listOf(WorkerCategory.BASIC to "أساسي", WorkerCategory.TEMP to "مؤقت"), category, { category = it })
            }
            SectionCard("طريقة الأجر") {
                Choice(listOf(PayType.DAILY to "يومية", PayType.CONTRACT to "مقاولة"), payType, { payType = it })
                val fromList = payType == PayType.DAILY && wageFromList
                if (payType == PayType.DAILY) {
                    Text("مصدر الأجر اليومي", style = MaterialTheme.typography.bodySmall)
                    Choice(
                        listOf("LIST" to "من قائمة أجور المكتب", "MANUAL" to "إدخال يدوي"),
                        if (wageFromList) "LIST" else "MANUAL",
                        { if (it == "LIST") wageFromList = true else if (manualAllowed) { wageFromList = false; rateId = 0L } }
                    )
                    if (!manualAllowed) Text(
                        "الإدخال اليدوي للأجر غير مسموح من المكتب. اختر من قائمة الأجور.",
                        style = MaterialTheme.typography.bodySmall, color = Bad
                    )
                }
                if (fromList) {
                    val activeRates = wageRates.filter { it.active || it.id == rateId }
                    if (activeRates.isEmpty()) Banner("لم يحدد المكتب قائمة الأجور بعد (الإعدادات ← إعدادات المكتب)", Bad)
                    DropdownField(
                        "الأجر حسب قائمة المكتب *", activeRates, activeRates.firstOrNull { it.id == rateId },
                        { "${it.title} - ${money(it.dailyWage, it.currency, currencies)}" },
                        { r ->
                            rateId = r.id; currency = r.currency
                            wage = Money.toInput(r.dailyWage); otRate = Money.toInput(r.overtimeRate)
                        }
                    )
                }
                DropdownField(
                    "عملة حساب العامل", currencies, currencies.firstOrNull { it.code == currency },
                    { currencyLabel(it) }, { currency = it.code }, enabled = !fromList
                )
                if (payType == PayType.DAILY) {
                    MoneyField("الأجر اليومي *", wage, { wage = it }, currency, currencies, enabled = !fromList)
                    MoneyField("سعر الساعة الإضافية", otRate, { otRate = it }, currency, currencies, enabled = !fromList)
                    Field("ساعات العمل الأساسية في اليوم", baseHours, { baseHours = it }, keyboard = KeyboardType.Number)
                } else {
                    Field("وصف العمل المتفق عليه", contractDesc, { contractDesc = it }, singleLine = false)
                    MoneyField("المبلغ المتفق عليه *", contractAmount, { contractAmount = it }, currency, currencies)
                    Field("نسبة الإنجاز % (اختياري)", progress, { progress = it }, keyboard = KeyboardType.Number)
                }
            }
            if (!isNew) SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("يعمل حالياً في المشروع", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                Text("عند انتهاء عمله أوقفه، فلا يظهر في كشف الحضور ويبقى حسابه محفوظاً.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Banner(it, Bad) }
            Button(
                enabled = loaded,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val w = Money.parse(wage)
                    val ot = Money.parse(otRate)
                    val ca = Money.parse(contractAmount)
                    val hours = com.alshihab.fieldledger.util.normalizeDigits(baseHours).trim().toIntOrNull()
                    val prog = com.alshihab.fieldledger.util.normalizeDigits(progress).trim().ifEmpty { "0" }.toIntOrNull()
                    error = when {
                        workerId == 0L -> "اختر العامل"
                        payType == PayType.DAILY && wageFromList && rateId == 0L -> "اختر الأجر من قائمة المكتب"
                        payType == PayType.DAILY && !wageFromList && !manualAllowed -> "الإدخال اليدوي غير مسموح"
                        payType == PayType.DAILY && (w == null || w <= 0) -> "أدخل أجراً يومياً صحيحاً"
                        payType == PayType.DAILY && ot == null -> "سعر الساعة الإضافية غير صحيح"
                        payType == PayType.DAILY && (hours == null || hours !in 1..24) -> "ساعات العمل غير صحيحة"
                        payType == PayType.CONTRACT && (ca == null || ca <= 0) -> "أدخل مبلغ المقاولة"
                        prog == null || prog !in 0..100 -> "نسبة الإنجاز بين 0 و 100"
                        else -> null
                    }
                    if (error != null) return@Button
                    val a = (original ?: Assignment(projectId = projectId, workerId = workerId)).copy(
                        id = assignmentId, projectId = projectId, workerId = workerId, category = category,
                        payType = payType, currency = currency,
                        dailyWage = if (payType == PayType.DAILY) w!! else 0L,
                        overtimeRate = if (payType == PayType.DAILY) ot!! else 0L,
                        baseHours = hours ?: 8,
                        contractDesc = contractDesc.trim(),
                        contractAmount = if (payType == PayType.CONTRACT) ca!! else 0L,
                        progress = prog!!, active = active,
                        wageRateId = if (payType == PayType.DAILY && wageFromList) rateId else 0L
                    )
                    scope.launch {
                        try {
                            repo.saveAssignment(a)
                            Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                            nav.popBackStack()
                        } catch (e: DuplicateException) {
                            error = e.message
                        }
                    }
                }
            ) { Text("حفظ") }
        }
    }
}

// =====================================================================
// 3F - daily attendance & withdrawals
// =====================================================================
internal data class RowState(
    val status: String,
    val ot: String = "",
    /** amount paid, in [payCurrency] */
    val withdrawn: String = "",
    val note: String = "",
    /** currency paid from the cash box; blank = the worker's account currency */
    val payCurrency: String = "",
    /** 1 unit of payCurrency = rate units of the account currency */
    val rate: String = ""
)

internal fun RowState.payCur(a: Assignment) = payCurrency.ifBlank { a.currency }

/** withdrawal converted to the worker's account currency; null if the rate is missing/invalid */
internal fun RowState.accountWithdrawn(a: Assignment): Long? {
    val paid = Money.parse(withdrawn) ?: return null
    if (payCur(a) == a.currency || paid == 0L) return paid
    val r = parseDecimal(rate) ?: return null
    if (r <= 0.0) return null
    return Math.round(paid * r)
}

internal fun defaultRow(a: Assignment) =
    RowState(if (a.payType == PayType.CONTRACT) Status.CONTRACT else Status.ABSENT)

internal fun computeDue(a: Assignment, r: RowState): Long {
    if (a.payType == PayType.CONTRACT) return 0L
    val base = when (r.status) {
        Status.PRESENT -> a.dailyWage
        Status.HALF -> a.dailyWage / 2
        else -> 0L
    }
    val ot = if (r.status == Status.ABSENT) 0.0 else (parseDecimal(r.ot) ?: 0.0)
    return base + Math.round(ot * a.overtimeRate)
}

// =====================================================================
// Worker account statement (كشف حساب عامل)
// =====================================================================
@Composable
fun WorkerStatementScreen(nav: NavController, assignmentId: Long) {
    val ctx = LocalContext.current
    val repo = Graph.repo
    val aw by rememberFlow<AssignmentWithWorker?>(assignmentId, initial = null) { repo.dao.assignmentFlow(assignmentId) }
    val rows by rememberFlow(assignmentId, initial = emptyList<Attendance>()) { repo.dao.attendanceForAssignment(assignmentId) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val cur = aw ?: run {
        Scaffold(topBar = { AppBar("كشف حساب عامل", onBack = { nav.popBackStack() }) }) { pad ->
            Column(Modifier.padding(pad)) { EmptyState("جارٍ التحميل…") }
        }
        return
    }
    val a = cur.a
    val c = a.currency
    val totalDue = rows.sumOf { it.due }
    val totalW = rows.sumOf { it.withdrawn }
    val days = rows.sumOf { if (it.status == Status.PRESENT) 1.0 else if (it.status == Status.HALF) 0.5 else 0.0 }
    val ot = rows.sumOf { it.overtimeHours }
    val contract = a.payType == PayType.CONTRACT

    Scaffold(topBar = {
        AppBar("كشف حساب: ${cur.worker.name}", onBack = { nav.popBackStack() }, actions = {
            if (cur.worker.phone.isNotBlank()) IconButton(onClick = {
                AppLock.allowExternal()
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cur.worker.phone)))
            }) {
                Icon(Icons.Filled.Call, contentDescription = "اتصال")
            }
            IconButton(onClick = { nav.navigate(Routes.assign(a.projectId, a.id)) }) {
                Icon(Icons.Filled.EditNote, contentDescription = "تعديل الاتفاق")
            }
            ExportButtons(a.projectId, com.alshihab.fieldledger.report.ReportKind.STATEMENT, "", "", a.id)
        })
    }) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionCard {
                    Text(cur.worker.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    KeyValue("المهنة", cur.worker.profession)
                    if (cur.worker.phone.isNotBlank()) KeyValue("الهاتف", cur.worker.phone)
                    KeyValue("رقم الكرت في المشروع", "${a.cardNo}")
                    KeyValue("التصنيف", if (a.category == WorkerCategory.BASIC) "أساسي" else "مؤقت")
                    KeyValue("الاتفاق", payLabel(a, currencies))
                    if (!contract && a.overtimeRate > 0) KeyValue("سعر الساعة الإضافية", money(a.overtimeRate, c, currencies))
                    if (contract && a.contractDesc.isNotBlank()) KeyValue("العمل", a.contractDesc)
                    if (contract && a.progress > 0) KeyValue("نسبة الإنجاز", "${a.progress}%")
                }
            }
            item {
                SectionCard("الخلاصة") {
                    if (contract) {
                        KeyValue("المبلغ المتفق عليه", money(a.contractAmount, c, currencies))
                        KeyValue("إجمالي الدفعات", money(totalW, c, currencies))
                        val rem = a.contractAmount - totalW
                        KeyValue("المتبقي من المقاولة", money(rem, c, currencies), if (rem < 0) Bad else Good, bold = true)
                        if (rem < 0) Banner("تنبيه: الدفعات تجاوزت قيمة المقاولة", Bad)
                    } else {
                        KeyValue("أيام العمل", fmtNum(days))
                        KeyValue("الساعات الإضافية", fmtNum(ot))
                        KeyValue("إجمالي المستحق", money(totalDue, c, currencies))
                        KeyValue("إجمالي المسحوب", money(totalW, c, currencies))
                        val rem = totalDue - totalW
                        KeyValue("المتبقي للعامل", money(rem, c, currencies), if (rem < 0) Bad else Good, bold = true)
                        if (rem < 0) Banner("تنبيه: المسحوب أكبر من المستحق", Bad)
                    }
                }
            }
            item { Text("الحركة اليومية", fontWeight = FontWeight.Bold, color = Brand) }
            if (rows.isEmpty()) item { EmptyState("لا توجد حركة بعد") }
            var running = 0L
            val runningList = rows.map { r ->
                running += if (contract) -r.withdrawn else r.due - r.withdrawn
                r to running
            }
            items(runningList, key = { it.first.id }) { (r, bal) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
                ) {
                    Row {
                        Text(Dates.label(r.date), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            when (r.status) {
                                Status.PRESENT -> "حاضر"
                                Status.HALF -> "نصف يوم"
                                Status.ABSENT -> "غائب"
                                else -> "مقاولة"
                            }
                        )
                    }
                    if (!contract) KeyValue("المستحق", money(r.due, c, currencies) + if (r.overtimeHours > 0) "  (+${fmtNum(r.overtimeHours)} س)" else "")
                    KeyValue("المسحوب", money(r.withdrawn, c, currencies))
                    if (r.payCurrency.isNotBlank() && r.payCurrency != c && r.payAmount != 0L)
                        Text("دُفع ${money(r.payAmount, r.payCurrency, currencies)} بسعر صرف ${fmtNum(r.rate)}", style = MaterialTheme.typography.bodySmall)
                    KeyValue(if (contract) "المتبقي من المقاولة" else "الرصيد التراكمي", money(if (contract) a.contractAmount + bal else bal, c, currencies), bold = true)
                    if (r.workNote.isNotBlank()) Text(r.workNote, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
