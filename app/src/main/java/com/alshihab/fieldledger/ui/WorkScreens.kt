package com.alshihab.fieldledger.ui

import android.widget.Toast
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
                DropdownField(
                    "العملة", currencies, currencies.firstOrNull { it.code == currency },
                    { currencyLabel(it) }, { currency = it.code }
                )
                if (payType == PayType.DAILY) {
                    Field("الأجر اليومي *", wage, { wage = it }, keyboard = KeyboardType.Decimal)
                    Field("سعر الساعة الإضافية", otRate, { otRate = it }, keyboard = KeyboardType.Decimal)
                    Field("ساعات العمل الأساسية في اليوم", baseHours, { baseHours = it }, keyboard = KeyboardType.Number)
                } else {
                    Field("وصف العمل المتفق عليه", contractDesc, { contractDesc = it }, singleLine = false)
                    Field("المبلغ المتفق عليه *", contractAmount, { contractAmount = it }, keyboard = KeyboardType.Decimal)
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
                        progress = prog!!, active = active
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
private data class RowState(
    val status: String,
    val ot: String = "",
    val withdrawn: String = "",
    val note: String = ""
)

private fun defaultRow(a: Assignment) =
    RowState(if (a.payType == PayType.CONTRACT) Status.CONTRACT else Status.ABSENT)

private fun computeDue(a: Assignment, r: RowState): Long {
    if (a.payType == PayType.CONTRACT) return 0L
    val base = when (r.status) {
        Status.PRESENT -> a.dailyWage
        Status.HALF -> a.dailyWage / 2
        else -> 0L
    }
    val ot = if (r.status == Status.ABSENT) 0.0 else (parseDecimal(r.ot) ?: 0.0)
    return base + Math.round(ot * a.overtimeRate)
}

@Composable
fun AttendanceScreen(nav: NavController, projectId: Long, initialDate: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    var date by rememberSaveable { mutableStateOf(initialDate.ifBlank { Dates.today() }) }
    val project by rememberFlow<Project?>(projectId, initial = null) { repo.dao.projectFlow(projectId) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val assignments by rememberFlow(projectId, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(projectId) }
    val sheet by rememberFlow<Sheet?>(projectId, date, initial = null) { repo.dao.sheetFlow(projectId, Forms.WORKERS, date) }
    val rows = remember { mutableStateMapOf<Long, RowState>() }
    var savedIds by remember { mutableStateOf(setOf<Long>()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(date) {
        loadedFor = null
        val s = repo.dao.findSheet(projectId, Forms.WORKERS, date)
        val existing = if (s != null) repo.dao.attendanceForSheet(s.id) else emptyList()
        rows.clear()
        existing.forEach {
            rows[it.assignmentId] = RowState(
                status = it.status,
                ot = if (it.overtimeHours == 0.0) "" else fmtNum(it.overtimeHours),
                withdrawn = Money.toInput(it.withdrawn),
                note = it.workNote
            )
        }
        savedIds = existing.map { it.assignmentId }.toSet()
        loadedFor = date
    }

    val editable = remember(sheet, loadedFor) { repo.sheetEditable(sheet) } && loadedFor == date
    val shown = assignments.filter { it.a.active || it.a.id in savedIds }
    fun rowOf(a: Assignment) = rows[a.id] ?: defaultRow(a)

    // totals per currency
    val dueTotals = mutableMapOf<String, Long>()
    val wTotals = mutableMapOf<String, Long>()
    var present = 0.0
    shown.forEach { aw ->
        val r = rowOf(aw.a)
        dueTotals[aw.a.currency] = (dueTotals[aw.a.currency] ?: 0L) + computeDue(aw.a, r)
        wTotals[aw.a.currency] = (wTotals[aw.a.currency] ?: 0L) + (Money.parse(r.withdrawn) ?: 0L)
        if (r.status == Status.PRESENT) present += 1.0
        if (r.status == Status.HALF) present += 0.5
    }

    Scaffold(
        topBar = {
            AppBar("كشف المسحوبات اليومية للعمال", onBack = { nav.popBackStack() }, actions = {
                if (editable) IconButton(onClick = {
                    shown.filter { it.a.payType == PayType.DAILY }.forEach { aw ->
                        rows[aw.a.id] = rowOf(aw.a).copy(status = Status.PRESENT)
                    }
                }) { Icon(Icons.Filled.DoneAll, contentDescription = "الكل حاضر") }
            })
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyValue("الحاضرون", fmtNum(present))
                    (dueTotals.keys + wTotals.keys).sorted().forEach { c ->
                        KeyValue(
                            "المستحق / المسحوب ($c)",
                            "${money(dueTotals[c] ?: 0L, c, currencies)}  /  ${money(wTotals[c] ?: 0L, c, currencies)}",
                            bold = true
                        )
                    }
                    error?.let { Text(it, color = Bad) }
                    Button(
                        enabled = editable && !saving && shown.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = {
                            val bad = shown.firstOrNull { aw ->
                                val r = rowOf(aw.a)
                                Money.parse(r.withdrawn) == null || parseDecimal(r.ot) == null || (parseDecimal(r.ot) ?: 0.0) < 0
                            }
                            if (bad != null) { error = "قيمة غير صحيحة في سطر ${bad.worker.name}"; return@Button }
                            error = null
                            val list = shown.map { aw ->
                                val r = rowOf(aw.a)
                                Attendance(
                                    sheetId = 0, projectId = projectId, assignmentId = aw.a.id, date = date,
                                    currency = aw.a.currency,
                                    status = if (aw.a.payType == PayType.CONTRACT) Status.CONTRACT else r.status,
                                    overtimeHours = if (r.status == Status.ABSENT) 0.0 else (parseDecimal(r.ot) ?: 0.0),
                                    due = computeDue(aw.a, r),
                                    withdrawn = Money.parse(r.withdrawn) ?: 0L,
                                    workNote = r.note.trim()
                                )
                            }
                            saving = true
                            scope.launch {
                                try {
                                    repo.saveAttendance(projectId, date, list)
                                    savedIds = savedIds + list.map { it.assignmentId }
                                    Toast.makeText(ctx, "تم حفظ الكشف", Toast.LENGTH_SHORT).show()
                                } catch (e: LockedException) {
                                    error = e.message
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    ) { Text(if (sheet == null) "حفظ الكشف" else "حفظ التعديلات") }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { DateNavigator(date) { date = it } }
            item {
                val p = project
                if (p != null) Text(
                    "${p.name} • ${listOf(p.village, p.uzla, p.district, p.governorate).filter { it.isNotBlank() }.joinToString(" / ")}" +
                        (sheet?.let { "  • كشف رقم ${it.serial}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { LockStatus(editable, sheet?.createdAt) }
            if (shown.isEmpty()) item {
                EmptyState("لا يوجد عمال نشطون في المشروع.\nأضف العمال من تبويب العمال في صفحة المشروع.")
            }
            items(shown, key = { it.a.id }) { aw ->
                val a = aw.a
                val r = rowOf(a)
                val due = computeDue(a, r)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(aw.worker.name, fontWeight = FontWeight.Bold)
                            Text("${aw.worker.profession} • ${payLabel(a, currencies)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (a.payType == PayType.DAILY) Text(
                            money(due, a.currency, currencies),
                            fontWeight = FontWeight.Bold,
                            color = if (due > 0) Good else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (a.payType == PayType.DAILY) {
                        Choice(
                            listOf(Status.PRESENT to "حاضر", Status.HALF to "نصف يوم", Status.ABSENT to "غائب"),
                            r.status, { rows[a.id] = r.copy(status = it) }, enabled = editable
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                "ساعات إضافية", r.ot, { rows[a.id] = r.copy(ot = it) },
                                modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal,
                                enabled = editable && r.status != Status.ABSENT
                            )
                            Field(
                                "المبلغ المسحوب", r.withdrawn, { rows[a.id] = r.copy(withdrawn = it) },
                                modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal, enabled = editable,
                                isError = Money.parse(r.withdrawn) == null
                            )
                        }
                    } else {
                        Field(
                            "دفعة من المقاولة (المبلغ المسحوب)", r.withdrawn, { rows[a.id] = r.copy(withdrawn = it) },
                            keyboard = KeyboardType.Decimal, enabled = editable,
                            isError = Money.parse(r.withdrawn) == null
                        )
                    }
                    Field("بيان الأعمال المنفذة", r.note, { rows[a.id] = r.copy(note = it) }, enabled = editable, singleLine = false)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

// =====================================================================
// Worker account statement (كشف حساب عامل)
// =====================================================================
@Composable
fun WorkerStatementScreen(nav: NavController, assignmentId: Long) {
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
            IconButton(onClick = { nav.navigate(Routes.assign(a.projectId, a.id)) }) {
                Icon(Icons.Filled.EditNote, contentDescription = "تعديل الاتفاق")
            }
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
                    if (cur.worker.cardNo.isNotBlank()) KeyValue("رقم الكرت", cur.worker.cardNo)
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
                    KeyValue(if (contract) "المتبقي من المقاولة" else "الرصيد التراكمي", money(if (contract) a.contractAmount + bal else bal, c, currencies), bold = true)
                    if (r.workNote.isNotBlank()) Text(r.workNote, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
