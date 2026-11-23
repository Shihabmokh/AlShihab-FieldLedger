package com.alshihab.fieldledger.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.AssignmentWithWorker
import com.alshihab.fieldledger.data.Attendance
import com.alshihab.fieldledger.data.CashEntry
import com.alshihab.fieldledger.data.CashKind
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.CurrencyTotal
import com.alshihab.fieldledger.data.Forms
import com.alshihab.fieldledger.data.LockedException
import com.alshihab.fieldledger.data.Material
import com.alshihab.fieldledger.data.MaterialMove
import com.alshihab.fieldledger.data.MoveType
import com.alshihab.fieldledger.data.StockException
import com.alshihab.fieldledger.data.StockQty
import com.alshihab.fieldledger.data.PayType
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.data.Sheet
import com.alshihab.fieldledger.data.Status
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money
import com.alshihab.fieldledger.util.fmtNum
import com.alshihab.fieldledger.util.parseDecimal
import kotlinx.coroutines.launch

/** A cash line being edited on the day screen. [key] is the entry id, or negative for unsaved lines. */
private data class CashDraft(
    val key: Long,
    val original: CashEntry?,
    val kind: String,
    val category: String,
    val currency: String,
    val amount: String,
    val docType: String,
    val docNo: String,
    val description: String,
    val voided: Boolean
) {
    fun toEntry(projectId: Long, date: String): CashEntry =
        (original ?: CashEntry(sheetId = 0, projectId = projectId, date = date, kind = kind, category = category, currency = currency, amount = 0))
            .copy(
                kind = kind, category = category, currency = currency,
                amount = Money.parse(amount) ?: 0L, docType = docType, docNo = docNo.trim(),
                description = description.trim(), voided = voided
            )

    companion object {
        fun of(e: CashEntry) = CashDraft(
            e.id, e, e.kind, e.category, e.currency, Money.toInput(e.amount),
            e.docType, e.docNo, e.description, e.voided
        )
    }
}

/** A material movement line being edited (purchase / office supply / issue). */
private data class MoveDraft(
    val key: Long,
    val original: MaterialMove?,
    val type: String,
    val materialId: Long,
    val qty: String,
    val unitPrice: String,
    val currency: String,
    val shop: String,
    val docNo: String,
    val site: String,
    val person: String,
    val details: String,
    val voided: Boolean
) {
    val qtyValue: Double get() = parseDecimal(qty) ?: 0.0
    val totalValue: Long get() = Math.round(qtyValue * (Money.parse(unitPrice) ?: 0L))

    fun toMove(projectId: Long, date: String): MaterialMove =
        (original ?: MaterialMove(sheetId = 0, projectId = projectId, materialId = materialId, date = date, type = type, qty = 0.0))
            .copy(
                materialId = materialId, type = type, qty = qtyValue,
                unitPrice = if (type == MoveType.PURCHASE) (Money.parse(unitPrice) ?: 0L) else 0L,
                total = if (type == MoveType.PURCHASE) totalValue else 0L,
                currency = if (type == MoveType.PURCHASE) currency else "",
                shop = shop.trim(), docNo = docNo.trim(), site = site.trim(), person = person.trim(),
                details = details.trim(), voided = voided
            )

    companion object {
        fun of(m: MaterialMove) = MoveDraft(
            m.id, m, m.type, m.materialId, fmtNum(m.qty), Money.toInput(m.unitPrice), m.currency,
            m.shop, m.docNo, m.site, m.person, m.details, m.voided
        )
    }
}

/**
 * "حركة اليوم": one screen for the whole day — workers (3F), receipts and expenses (A2-F / B2-F) —
 * with a live cash-box balance and a single save.
 */
@Composable
fun DayEntryScreen(nav: NavController, projectId: Long, initialDate: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    var date by rememberSaveable { mutableStateOf(initialDate.ifBlank { Dates.today() }) }
    val project by rememberFlow<Project?>(projectId, initial = null) { repo.dao.projectFlow(projectId) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val assignments by rememberFlow(projectId, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(projectId) }
    val workSheet by rememberFlow<Sheet?>(projectId, date, initial = null) { repo.dao.sheetFlow(projectId, Forms.WORKERS, date) }
    val cashSheet by rememberFlow<Sheet?>(projectId, "c$date", initial = null) { repo.dao.sheetFlow(projectId, Forms.CASH, date) }
    val netBefore by rememberFlow(projectId, date, initial = emptyList<CurrencyTotal>()) { repo.dao.cashNetBefore(projectId, date) }
    val wBefore by rememberFlow(projectId, "w$date", initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotalsBefore(projectId, date) }

    val materials by rememberFlow(projectId, "mat", initial = emptyList<Material>()) { repo.dao.materials(projectId) }
    val stock by rememberFlow(projectId, "stk", initial = emptyList<StockQty>()) { repo.dao.stock(projectId) }
    val pBefore by rememberFlow(projectId, "pb$date", initial = emptyList<CurrencyTotal>()) { repo.dao.purchaseTotalsBefore(projectId, date) }
    val purchaseSheet by rememberFlow<Sheet?>(projectId, "4$date", initial = null) { repo.dao.sheetFlow(projectId, Forms.PURCHASE, date) }
    val officeSheet by rememberFlow<Sheet?>(projectId, "o$date", initial = null) { repo.dao.sheetFlow(projectId, Forms.OFFICE, date) }
    val issueSheet by rememberFlow<Sheet?>(projectId, "i$date", initial = null) { repo.dao.sheetFlow(projectId, Forms.ISSUE, date) }
    val moves = remember { mutableStateListOf<MoveDraft>() }
    var newMaterialFor by remember { mutableStateOf<Long?>(null) }
    val rows = remember { mutableStateMapOf<Long, RowState>() }
    val cash = remember { mutableStateListOf<CashDraft>() }
    var savedIds by remember { mutableStateOf(setOf<Long>()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableLongStateOf(-1L) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var workersOpen by rememberSaveable { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(date, reloadTick) {
        loadedFor = null
        val ws = repo.dao.findSheet(projectId, Forms.WORKERS, date)
        val existing = if (ws != null) repo.dao.attendanceForSheet(ws.id) else emptyList()
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
        cash.clear()
        cash.addAll(repo.dao.cashOnList(projectId, date).map { CashDraft.of(it) })
        moves.clear()
        moves.addAll(repo.dao.movesOnList(projectId, date).map { MoveDraft.of(it) })
        error = null
        loadedFor = date
    }

    val loaded = loadedFor == date
    val workEditable = remember(workSheet, loadedFor) { repo.sheetEditable(workSheet) } && loaded
    val cashEditable = remember(cashSheet, loadedFor) { repo.sheetEditable(cashSheet) } && loaded
    val purchaseEditable = remember(purchaseSheet, loadedFor) { repo.sheetEditable(purchaseSheet) } && loaded
    val officeEditable = remember(officeSheet, loadedFor) { repo.sheetEditable(officeSheet) } && loaded
    val issueEditable = remember(issueSheet, loadedFor) { repo.sheetEditable(issueSheet) } && loaded
    fun moveEditable(type: String) = when (type) {
        MoveType.PURCHASE -> purchaseEditable
        MoveType.OFFICE -> officeEditable
        else -> issueEditable
    }
    val stockMap = stock.associate { it.materialId to it.qty }
    val shown = assignments.filter { it.a.active || it.a.id in savedIds }
    fun rowOf(aw: AssignmentWithWorker) = rows[aw.a.id] ?: defaultRow(aw.a)
    val baseCurrency = project?.baseCurrency ?: "YER"

    // ---- live totals ----
    val dueToday = mutableMapOf<String, Long>()
    val wToday = mutableMapOf<String, Long>()
    var present = 0.0
    shown.forEach { aw ->
        val r = rowOf(aw)
        val c = aw.a.currency
        dueToday[c] = (dueToday[c] ?: 0L) + computeDue(aw.a, r)
        wToday[c] = (wToday[c] ?: 0L) + (Money.parse(r.withdrawn) ?: 0L)
        if (r.status == Status.PRESENT) present += 1.0
        if (r.status == Status.HALF) present += 0.5
    }
    val receipts = mutableMapOf<String, Long>()
    val expenses = mutableMapOf<String, Long>()
    cash.filter { !it.voided }.forEach { d ->
        val amt = Money.parse(d.amount) ?: 0L
        val m = if (d.kind == CashKind.IN) receipts else expenses
        m[d.currency] = (m[d.currency] ?: 0L) + amt
    }
    val purchasesToday = mutableMapOf<String, Long>()
    moves.filter { !it.voided && it.type == MoveType.PURCHASE }.forEach { d ->
        purchasesToday[d.currency] = (purchasesToday[d.currency] ?: 0L) + d.totalValue
    }
    val carried = mergeMinus(mergeMinus(netBefore.toMap(), wBefore.toMap()), pBefore.toMap())
    val codes = (carried.keys + receipts.keys + expenses.keys + wToday.keys + purchasesToday.keys).filter {
        (carried[it] ?: 0L) != 0L || (receipts[it] ?: 0L) != 0L || (expenses[it] ?: 0L) != 0L ||
            (wToday[it] ?: 0L) != 0L || (purchasesToday[it] ?: 0L) != 0L
    }.sorted()

    fun addCash(kind: String) {
        cash.add(
            CashDraft(
                key = newKey, original = null, kind = kind,
                category = if (kind == CashKind.IN) IN_CATEGORIES.first() else OUT_CATEGORIES.first(),
                currency = baseCurrency, amount = "",
                docType = if (kind == CashKind.IN) "سند قبض" else "سند صرف",
                docNo = "", description = "", voided = false
            )
        )
        newKey -= 1
    }

    fun addMove(type: String) {
        moves.add(
            MoveDraft(
                key = newKey, original = null, type = type,
                materialId = materials.firstOrNull { it.active }?.id ?: 0L,
                qty = "", unitPrice = "", currency = baseCurrency, shop = "", docNo = "",
                site = project?.site ?: "", person = "", details = "", voided = false
            )
        )
        newKey -= 1
    }

    fun save() {
        val badRow = shown.firstOrNull { aw ->
            val r = rowOf(aw)
            Money.parse(r.withdrawn) == null || parseDecimal(r.ot) == null || (parseDecimal(r.ot) ?: 0.0) < 0
        }
        if (workEditable && badRow != null) { error = "قيمة غير صحيحة في سطر العامل ${badRow.worker.name}"; return }
        val badCash = cash.firstOrNull { !it.voided && it.original == null && (Money.parse(it.amount) ?: 0L) <= 0L && it.description.isNotBlank() }
            ?: cash.firstOrNull { !it.voided && it.original != null && (Money.parse(it.amount) ?: 0L) <= 0L }
        if (cashEditable && badCash != null) { error = "أدخل مبلغ القيد: ${badCash.category}"; return }
        val badMove = moves.firstOrNull { d ->
            !d.voided && moveEditable(d.type) && (d.original != null || d.qty.isNotBlank()) &&
                (d.materialId == 0L || (parseDecimal(d.qty) ?: 0.0) <= 0.0 ||
                    (d.type == MoveType.PURCHASE && (Money.parse(d.unitPrice) ?: 0L) <= 0L))
        }
        if (badMove != null) {
            error = when {
                badMove.materialId == 0L -> "اختر الصنف في سطر المواد"
                (parseDecimal(badMove.qty) ?: 0.0) <= 0.0 -> "أدخل الكمية في سطر المواد"
                else -> "أدخل سعر الوحدة في سطر الشراء"
            }
            return
        }
        error = null

        val attendance = if (!workEditable) null else shown.map { aw ->
            val r = rowOf(aw)
            Attendance(
                sheetId = 0, projectId = projectId, assignmentId = aw.a.id, date = date, currency = aw.a.currency,
                status = if (aw.a.payType == PayType.CONTRACT) Status.CONTRACT else r.status,
                overtimeHours = if (r.status == Status.ABSENT) 0.0 else (parseDecimal(r.ot) ?: 0.0),
                due = computeDue(aw.a, r),
                withdrawn = Money.parse(r.withdrawn) ?: 0L,
                workNote = r.note.trim()
            )
        }
        val cashEntries = if (!cashEditable) null else cash.map { it.toEntry(projectId, date) }
        val moveMap = listOf(MoveType.PURCHASE, MoveType.OFFICE, MoveType.ISSUE)
            .filter { moveEditable(it) }
            .associateWith { t -> moves.filter { it.type == t && it.materialId != 0L }.map { it.toMove(projectId, date) } }
            .filterValues { it.isNotEmpty() }
        saving = true
        scope.launch {
            try {
                repo.saveDay(projectId, date, attendance, cashEntries, moveMap)
                Toast.makeText(ctx, "تم حفظ حركة اليوم", Toast.LENGTH_SHORT).show()
                reloadTick++
            } catch (e: LockedException) {
                error = e.message
            } catch (e: StockException) {
                error = e.message
            } catch (e: Exception) {
                error = "تعذر الحفظ: ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar("حركة اليوم", onBack = { nav.popBackStack() }, actions = {
                if (workEditable) IconButton(onClick = {
                    shown.filter { it.a.payType == PayType.DAILY }.forEach { aw ->
                        rows[aw.a.id] = rowOf(aw).copy(status = Status.PRESENT)
                    }
                    workersOpen = true
                }) { Icon(Icons.Filled.DoneAll, contentDescription = "الكل حاضر") }
            })
        },
        bottomBar = {
            Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    codes.forEach { c ->
                        val current = (carried[c] ?: 0L) + (receipts[c] ?: 0L) - (expenses[c] ?: 0L) - (wToday[c] ?: 0L) - (purchasesToday[c] ?: 0L)
                        KeyValue(
                            "رصيد الصندوق ($c)",
                            money(current, c, currencies),
                            if (current < 0) Bad else Good, bold = true
                        )
                    }
                    error?.let { Text(it, color = Bad) }
                    Button(
                        enabled = (workEditable || cashEditable || purchaseEditable || officeEditable || issueEditable) && !saving,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = { save() }
                    ) { Text(if (saving) "جارٍ الحفظ…" else "حفظ حركة اليوم") }
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
                    "${p.name} • " + listOf(p.village, p.uzla, p.district, p.governorate).filter { it.isNotBlank() }.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!loaded) item { EmptyState("جارٍ التحميل…") }

            // ================= summary =================
            if (loaded) item {
                SectionCard("ملخص اليوم") {
                    KeyValue("الحاضرون", fmtNum(present))
                    codes.forEach { c ->
                        Text(currencies.firstOrNull { it.code == c }?.name ?: c, fontWeight = FontWeight.Bold, color = Brand)
                        KeyValue("الرصيد المرحّل", money(carried[c] ?: 0L, c, currencies))
                        KeyValue("+ المقبوضات", money(receipts[c] ?: 0L, c, currencies), Good)
                        KeyValue("− مسحوبات العمال", money(wToday[c] ?: 0L, c, currencies), Bad)
                        KeyValue("− مشتريات المواد", money(purchasesToday[c] ?: 0L, c, currencies), Bad)
                        KeyValue("− المصروفات الأخرى", money(expenses[c] ?: 0L, c, currencies), Bad)
                        if ((dueToday[c] ?: 0L) > 0) KeyValue("مستحقات العمال اليوم", money(dueToday[c] ?: 0L, c, currencies))
                    }
                }
            }

            // ================= workers (3F) =================
            if (loaded) item {
                SectionHeader(
                    title = "العمال - كشف المسحوبات (3F)" + (workSheet?.let { "  رقم ${it.serial}" } ?: ""),
                    subtitle = "${shown.size} عامل",
                    open = workersOpen,
                    onToggle = { workersOpen = !workersOpen }
                )
            }
            if (loaded && workSheet != null) item { LockStatus(workEditable, workSheet?.createdAt) }
            if (loaded && workersOpen && shown.isEmpty()) item {
                EmptyState("لا يوجد عمال نشطون. أضفهم من تبويب العمال في صفحة المشروع.")
            }
            if (loaded && workersOpen) items(shown, key = { "w" + it.a.id }) { aw ->
                WorkerDayCard(aw, rowOf(aw), currencies, workEditable) { rows[aw.a.id] = it }
            }

            // ================= receipts =================
            if (loaded) item {
                SectionHeader("المقبوضات", "${cash.count { it.kind == CashKind.IN && !it.voided }} قيد", null, null)
            }
            if (loaded && cashSheet != null) item { LockStatus(cashEditable, cashSheet?.createdAt) }
            if (loaded) items(cash.filter { it.kind == CashKind.IN }, key = { "c" + it.key }) { d ->
                CashDraftCard(d, currencies, cashEditable,
                    onChange = { nd -> val i = cash.indexOfFirst { it.key == d.key }; if (i >= 0) cash[i] = nd },
                    onRemove = { cash.removeAll { it.key == d.key } })
            }
            if (loaded) item {
                OutlinedButton(onClick = { addCash(CashKind.IN) }, enabled = cashEditable, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة مقبوض")
                }
            }

            // ================= expenses =================
            if (loaded) item {
                SectionHeader("المصروفات الأخرى (B2-F)", "${cash.count { it.kind == CashKind.OUT && !it.voided }} قيد", null, null)
            }
            if (loaded) items(cash.filter { it.kind == CashKind.OUT }, key = { "c" + it.key }) { d ->
                CashDraftCard(d, currencies, cashEditable,
                    onChange = { nd -> val i = cash.indexOfFirst { it.key == d.key }; if (i >= 0) cash[i] = nd },
                    onRemove = { cash.removeAll { it.key == d.key } })
            }
            if (loaded) item {
                OutlinedButton(onClick = { addCash(CashKind.OUT) }, enabled = cashEditable, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة مصروف")
                }
            }

            if (loaded) item {
                SectionHeader("مشتريات المواد (4F)", "${moves.count { it.type == MoveType.PURCHASE && !it.voided }} سطر", null, null)
            }
            if (loaded && purchaseSheet != null) item { LockStatus(moveEditable(MoveType.PURCHASE), purchaseSheet?.createdAt) }
            if (loaded) items(moves.filter { it.type == MoveType.PURCHASE }, key = { "m" + it.key }) { d ->
                MoveDraftCard(d, materials, stockMap, currencies, moveEditable(d.type),
                    onChange = { nd -> val i = moves.indexOfFirst { it.key == d.key }; if (i >= 0) moves[i] = nd },
                    onRemove = { moves.removeAll { it.key == d.key } },
                    onNewMaterial = { newMaterialFor = d.key })
            }
            if (loaded) item {
                OutlinedButton(onClick = { addMove(MoveType.PURCHASE) }, enabled = moveEditable(MoveType.PURCHASE), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة مادة مشتراة")
                }
            }
            if (loaded) item {
                SectionHeader("مواد موردة من المكتب", "${moves.count { it.type == MoveType.OFFICE && !it.voided }} سطر", null, null)
            }
            if (loaded && officeSheet != null) item { LockStatus(moveEditable(MoveType.OFFICE), officeSheet?.createdAt) }
            if (loaded) items(moves.filter { it.type == MoveType.OFFICE }, key = { "m" + it.key }) { d ->
                MoveDraftCard(d, materials, stockMap, currencies, moveEditable(d.type),
                    onChange = { nd -> val i = moves.indexOfFirst { it.key == d.key }; if (i >= 0) moves[i] = nd },
                    onRemove = { moves.removeAll { it.key == d.key } },
                    onNewMaterial = { newMaterialFor = d.key })
            }
            if (loaded) item {
                OutlinedButton(onClick = { addMove(MoveType.OFFICE) }, enabled = moveEditable(MoveType.OFFICE), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة مادة موردة")
                }
            }
            if (loaded) item {
                SectionHeader("مواد منصرفة من المخزن (31F)", "${moves.count { it.type == MoveType.ISSUE && !it.voided }} سطر", null, null)
            }
            if (loaded && issueSheet != null) item { LockStatus(moveEditable(MoveType.ISSUE), issueSheet?.createdAt) }
            if (loaded) items(moves.filter { it.type == MoveType.ISSUE }, key = { "m" + it.key }) { d ->
                MoveDraftCard(d, materials, stockMap, currencies, moveEditable(d.type),
                    onChange = { nd -> val i = moves.indexOfFirst { it.key == d.key }; if (i >= 0) moves[i] = nd },
                    onRemove = { moves.removeAll { it.key == d.key } },
                    onNewMaterial = { newMaterialFor = d.key })
            }
            if (loaded) item {
                OutlinedButton(onClick = { addMove(MoveType.ISSUE) }, enabled = moveEditable(MoveType.ISSUE), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة مادة منصرفة")
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    newMaterialFor?.let { key ->
        MaterialDialog(projectId, null) { id ->
            if (id != null) {
                val i = moves.indexOfFirst { it.key == key }
                if (i >= 0) moves[i] = moves[i].copy(materialId = id)
            }
            newMaterialFor = null
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, open: Boolean?, onToggle: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (open != null) Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun WorkerDayCard(
    aw: AssignmentWithWorker,
    r: RowState,
    currencies: List<CurrencyEntity>,
    editable: Boolean,
    onChange: (RowState) -> Unit
) {
    val a = aw.a
    val due = computeDue(a, r)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${a.cardNo}. ${aw.worker.name}", fontWeight = FontWeight.Bold)
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
                r.status, { onChange(r.copy(status = it)) }, enabled = editable
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    "ساعات إضافية", r.ot, { onChange(r.copy(ot = it)) },
                    modifier = Modifier.weight(0.8f), keyboard = KeyboardType.Decimal,
                    enabled = editable && r.status != Status.ABSENT
                )
                MoneyField(
                    "المسحوب", r.withdrawn, { onChange(r.copy(withdrawn = it)) }, a.currency, currencies,
                    modifier = Modifier.weight(1.2f), enabled = editable, showWords = false
                )
            }
        } else {
            MoneyField(
                "دفعة من المقاولة", r.withdrawn, { onChange(r.copy(withdrawn = it)) }, a.currency, currencies,
                enabled = editable, showWords = false
            )
        }
        Field("بيان الأعمال المنفذة", r.note, { onChange(r.copy(note = it)) }, enabled = editable, singleLine = false)
    }
}

@Composable
private fun CashDraftCard(
    d: CashDraft,
    currencies: List<CurrencyEntity>,
    editable: Boolean,
    onChange: (CashDraft) -> Unit,
    onRemove: () -> Unit
) {
    val isIn = d.kind == CashKind.IN
    val color = if (isIn) Good else Bad
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .width(6.dp)
                    .height(22.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
            Text(
                (if (isIn) "  قبض" else "  صرف") + if (d.original == null) " (جديد)" else "",
                fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f),
                textDecoration = if (d.voided) TextDecoration.LineThrough else TextDecoration.None
            )
            if (editable) {
                if (d.original == null) IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "حذف السطر")
                } else if (!d.original.voided) TextButton(onClick = { onChange(d.copy(voided = !d.voided)) }) {
                    if (d.voided) { Icon(Icons.Filled.Undo, null); Text(" تراجع") } else Text("إلغاء القيد", color = Bad)
                }
            }
        }
        if (d.voided) Banner(if (d.original?.voided == true) "قيد ملغى" else "سيُلغى هذا القيد عند الحفظ", Bad)
        val enabled = editable && !d.voided
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                if (isIn) "المصدر" else "البند",
                if (isIn) IN_CATEGORIES else OUT_CATEGORIES,
                d.category, { it }, { onChange(d.copy(category = it)) },
                modifier = Modifier.weight(1.2f), enabled = enabled
            )
            DropdownField(
                "العملة", currencies, currencies.firstOrNull { it.code == d.currency },
                { it.code }, { onChange(d.copy(currency = it.code)) },
                modifier = Modifier.weight(0.8f), enabled = enabled
            )
        }
        MoneyField("المبلغ", d.amount, { onChange(d.copy(amount = it)) }, d.currency, currencies, enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                "المستند", DOC_TYPES, d.docType, { it }, { onChange(d.copy(docType = it)) },
                modifier = Modifier.weight(1f), enabled = enabled
            )
            Field("رقمه", d.docNo, { onChange(d.copy(docNo = it)) }, modifier = Modifier.weight(0.8f), enabled = enabled)
        }
        Field("البيان", d.description, { onChange(d.copy(description = it)) }, enabled = enabled, singleLine = false)
    }
}

@Composable
private fun MoveDraftCard(
    d: MoveDraft,
    materials: List<Material>,
    stock: Map<Long, Double>,
    currencies: List<CurrencyEntity>,
    editable: Boolean,
    onChange: (MoveDraft) -> Unit,
    onRemove: () -> Unit,
    onNewMaterial: () -> Unit
) {
    val (label, color) = when (d.type) {
        MoveType.PURCHASE -> "شراء" to Good
        MoveType.OFFICE -> "توريد من المكتب" to Accent
        else -> "صرف من المخزن" to Bad
    }
    val mat = materials.firstOrNull { it.id == d.materialId }
    val enabled = editable && !d.voided
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .width(6.dp)
                    .height(22.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
            Text(
                "  $label" + if (d.original == null) " (جديد)" else "",
                fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f),
                textDecoration = if (d.voided) TextDecoration.LineThrough else TextDecoration.None
            )
            if (editable) {
                if (d.original == null) IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "حذف السطر")
                } else if (!d.original.voided) TextButton(onClick = { onChange(d.copy(voided = !d.voided)) }) {
                    if (d.voided) { Icon(Icons.Filled.Undo, null); Text(" تراجع") } else Text("إلغاء الحركة", color = Bad)
                }
            }
        }
        if (d.voided) Banner(if (d.original?.voided == true) "حركة ملغاة" else "ستُلغى هذه الحركة عند الحفظ", Bad)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropdownField(
                "الصنف", materials.filter { it.active || it.id == d.materialId }, mat,
                { "${it.name} (${it.unit})" }, { onChange(d.copy(materialId = it.id)) },
                modifier = Modifier.weight(1f), enabled = enabled
            )
            if (enabled) IconButton(onClick = onNewMaterial) { Icon(Icons.Filled.Add, contentDescription = "صنف جديد") }
        }
        if (mat != null) Text(
            "الرصيد الحالي في المخزن: ${fmtNum(stock[mat.id] ?: 0.0)} ${mat.unit}",
            style = MaterialTheme.typography.bodySmall,
            color = if (d.type == MoveType.ISSUE && (parseDecimal(d.qty) ?: 0.0) > (stock[mat.id] ?: 0.0) + (if (d.original != null && !d.original.voided) d.original.qty else 0.0)) Bad
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (d.type) {
            MoveType.PURCHASE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("الكمية", d.qty, { onChange(d.copy(qty = it)) }, modifier = Modifier.weight(0.8f), keyboard = KeyboardType.Decimal, enabled = enabled)
                    DropdownField(
                        "العملة", currencies, currencies.firstOrNull { it.code == d.currency },
                        { it.code }, { onChange(d.copy(currency = it.code)) },
                        modifier = Modifier.weight(0.8f), enabled = enabled
                    )
                }
                MoneyField("سعر الوحدة", d.unitPrice, { onChange(d.copy(unitPrice = it)) }, d.currency, currencies, enabled = enabled, showWords = false)
                KeyValue("إجمالي القيمة", money(d.totalValue, d.currency, currencies), Brand, bold = true)
                if (d.totalValue > 0) Text(
                    com.alshihab.fieldledger.util.Tafqeet.amount(
                        d.totalValue,
                        currencies.firstOrNull { it.code == d.currency }?.name?.substringBefore(" (") ?: d.currency
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("اسم المحل", d.shop, { onChange(d.copy(shop = it)) }, modifier = Modifier.weight(1.2f), enabled = enabled)
                    Field("رقم الفاتورة", d.docNo, { onChange(d.copy(docNo = it)) }, modifier = Modifier.weight(0.8f), enabled = enabled)
                }
            }
            MoveType.OFFICE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("الكمية", d.qty, { onChange(d.copy(qty = it)) }, modifier = Modifier.weight(0.8f), keyboard = KeyboardType.Decimal, enabled = enabled)
                    Field("رقم سند التوريد", d.docNo, { onChange(d.copy(docNo = it)) }, modifier = Modifier.weight(1f), enabled = enabled)
                }
                Field("المستلم", d.person, { onChange(d.copy(person = it)) }, enabled = enabled)
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("الكمية", d.qty, { onChange(d.copy(qty = it)) }, modifier = Modifier.weight(0.8f), keyboard = KeyboardType.Decimal, enabled = enabled)
                    Field("موقع العمل", d.site, { onChange(d.copy(site = it)) }, modifier = Modifier.weight(1.2f), enabled = enabled)
                }
                Field("القائم بالعمل", d.person, { onChange(d.copy(person = it)) }, enabled = enabled)
            }
        }
        Field(if (d.type == MoveType.ISSUE) "تفاصيل العمل" else "ملاحظات", d.details, { onChange(d.copy(details = it)) }, enabled = enabled, singleLine = false)
    }
}
