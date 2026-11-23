package com.alshihab.fieldledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.AssignmentTotal
import com.alshihab.fieldledger.data.AssignmentWithWorker
import com.alshihab.fieldledger.data.Attendance
import com.alshihab.fieldledger.data.CashEntry
import com.alshihab.fieldledger.data.CashKind
import com.alshihab.fieldledger.data.CategoryTotal
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.CurrencyTotal
import com.alshihab.fieldledger.data.Forms
import com.alshihab.fieldledger.data.PayType
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.data.Sheet
import com.alshihab.fieldledger.data.Status
import com.alshihab.fieldledger.data.WorkerCategory
import com.alshihab.fieldledger.security.TimeGuard
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.fmtNum

@Composable
fun ProjectHome(nav: NavController, id: Long) {
    val repo = Graph.repo
    val project by rememberFlow<Project?>(id, initial = null) { repo.dao.projectFlow(id) }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cashDate by rememberSaveable { mutableStateOf(Dates.today()) }

    Scaffold(
        topBar = {
            AppBar(project?.name ?: "", onBack = { nav.popBackStack() }, actions = {
                IconButton(onClick = { nav.navigate(Routes.projectForm(id)) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "بيانات المشروع")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                val tabs = listOf(
                    "الرئيسية" to Icons.Filled.Dashboard,
                    "العمال" to Icons.Filled.Engineering,
                    "الصندوق" to Icons.Filled.AccountBalanceWallet,
                    "المخزن" to Icons.Filled.Inventory2,
                    "التقارير" to Icons.Filled.Assessment
                )
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, maxLines = 1) },
                    alwaysShowLabel = true
                    )
                }
            }
        }
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            val p = project
            if (p != null) when (tab) {
                0 -> DashboardTab(nav, p, currencies)
                1 -> ProjectWorkersTab(nav, p, currencies)
                2 -> CashTab(nav, p, currencies, cashDate) { cashDate = it }
                3 -> StoreTab(nav, p, currencies)
                else -> ReportsTab(p, currencies)
            }
        }
    }
}

@Composable
private fun DashboardTab(nav: NavController, p: Project, currencies: List<CurrencyEntity>) {
    val ctx = LocalContext.current
    val repo = Graph.repo
    val today = remember { Dates.today() }
    val cashNet by rememberFlow(p.id, initial = emptyList<CurrencyTotal>()) { repo.dao.cashNet(p.id) }
    val withdrawn by rememberFlow(p.id, initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotals(p.id) }
    val purchases by rememberFlow(p.id, "p", initial = emptyList<CurrencyTotal>()) { repo.dao.purchaseTotals(p.id) }
    val todayRows by rememberFlow(p.id, today, initial = emptyList<Attendance>()) { repo.dao.attendanceOn(p.id, today) }
    val assignments by rememberFlow(p.id, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(p.id) }
    val clockOk = remember { TimeGuard.clockOk(ctx) }
    val balance = mergeMinus(mergeMinus(cashNet.toMap(), withdrawn.toMap()), purchases.toMap())

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!clockOk) item {
            Banner("ساعة الجهاز أقدم من آخر وقت مسجل. التعديل موقوف حتى تُضبط الساعة بشكل صحيح.", Bad)
        }
        if (!p.active) item { Banner("هذا المشروع موقوف.", Warn) }
        item {
            SectionCard("رصيد الصندوق الحالي") {
                if (balance.isEmpty()) Text("لا توجد حركة بعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
                balance.toSortedMap().forEach { (code, v) ->
                    KeyValue(
                        currencies.firstOrNull { it.code == code }?.name ?: code,
                        money(v, code, currencies),
                        if (v < 0) Bad else Good, bold = true
                    )
                }
            }
        }
        item {
            SectionCard("اليوم - ${Dates.label(today)}") {
                val active = assignments.count { it.a.active }
                val present = todayRows.count { it.status == Status.PRESENT || it.status == Status.HALF }
                KeyValue("العمال النشطون في المشروع", "$active")
                KeyValue("الحاضرون اليوم", "$present")
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { nav.navigate(Routes.attendance(p.id, today)) },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(Icons.Filled.FactCheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("إدخال حركة اليوم (العمال + الصندوق)")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { nav.navigate(Routes.cashForm(p.id, today, 0, CashKind.IN)) },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.Add, null); Text(" قبض") }
                    OutlinedButton(
                        onClick = { nav.navigate(Routes.cashForm(p.id, today, 0, CashKind.OUT)) },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.Remove, null); Text(" صرف") }
                }
            }
        }
        item {
            SectionCard("بيانات المشروع") {
                KeyValue("المحافظة / المديرية", listOf(p.governorate, p.district).filter { it.isNotBlank() }.joinToString(" / "))
                KeyValue("العزلة / القرية", listOf(p.uzla, p.village).filter { it.isNotBlank() }.joinToString(" / "))
                if (p.site.isNotBlank()) KeyValue("موقع العمل", p.site)
                if (p.client.isNotBlank()) KeyValue("الجهة", p.client)
                if (p.contractNo.isNotBlank()) KeyValue("رقم العقد", p.contractNo)
                KeyValue("العملة الأساسية", p.baseCurrency)
            }
        }
    }
}

fun payLabel(a: com.alshihab.fieldledger.data.Assignment, currencies: List<CurrencyEntity>): String =
    if (a.payType == PayType.CONTRACT) "مقاولة: ${money(a.contractAmount, a.currency, currencies)}"
    else "يومية: ${money(a.dailyWage, a.currency, currencies)}"

@Composable
private fun ProjectWorkersTab(nav: NavController, p: Project, currencies: List<CurrencyEntity>) {
    val repo = Graph.repo
    val assignments by rememberFlow(p.id, initial = emptyList<AssignmentWithWorker>()) { repo.dao.assignments(p.id) }
    val totals by rememberFlow(p.id, initial = emptyList<AssignmentTotal>()) { repo.dao.assignmentTotals(p.id) }
    val totalMap = totals.associateBy { it.assignmentId }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate(Routes.assign(p.id, 0)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Text(" إضافة عامل للمشروع")
                }
                OutlinedButton(onClick = { nav.navigate(Routes.attendance(p.id, Dates.today())) }, modifier = Modifier.weight(1f)) {
                    Text("حركة اليوم")
                }
            }
        }
        if (assignments.isEmpty()) item { EmptyState("لم يُضف أي عامل لهذا المشروع بعد") }
        items(assignments, key = { it.a.id }) { aw ->
            val a = aw.a
            val t = totalMap[a.id]
            val withdrawnSum = t?.withdrawn ?: 0L
            SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.statement(a.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.cardNo}. ${aw.worker.name}" + if (!a.active) "  (انتهى عمله)" else "", fontWeight = FontWeight.Bold)
                        Text(
                            "${aw.worker.profession} • ${if (a.category == WorkerCategory.BASIC) "أساسي" else "مؤقت"} • ${payLabel(a, currencies)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { nav.navigate(Routes.assign(p.id, a.id)) }) {
                        Icon(Icons.Filled.EditNote, contentDescription = "تعديل الاتفاق")
                    }
                }
                HorizontalDivider()
                if (a.payType == PayType.CONTRACT) {
                    KeyValue("المسحوب", money(withdrawnSum, a.currency, currencies))
                    KeyValue("المتبقي من المقاولة", money(a.contractAmount - withdrawnSum, a.currency, currencies), bold = true)
                } else {
                    val due = t?.due ?: 0L
                    KeyValue("أيام العمل", fmtNum(t?.days ?: 0.0) + if ((t?.ot ?: 0.0) > 0) "  + ${fmtNum(t!!.ot)} ساعة إضافية" else "")
                    KeyValue("المستحق", money(due, a.currency, currencies))
                    KeyValue("المسحوب", money(withdrawnSum, a.currency, currencies))
                    KeyValue("المتبقي للعامل", money(due - withdrawnSum, a.currency, currencies), bold = true)
                }
            }
        }
    }
}

@Composable
private fun CashTab(
    nav: NavController,
    p: Project,
    currencies: List<CurrencyEntity>,
    date: String,
    onDate: (String) -> Unit
) {
    val repo = Graph.repo
    val sheet by rememberFlow<Sheet?>(p.id, date, initial = null) { repo.dao.sheetFlow(p.id, Forms.CASH, date) }
    val entries by rememberFlow(p.id, date, initial = emptyList<CashEntry>()) { repo.dao.cashOn(p.id, date) }
    val netBefore by rememberFlow(p.id, date, initial = emptyList<CurrencyTotal>()) { repo.dao.cashNetBefore(p.id, date) }
    val wBefore by rememberFlow(p.id, date, initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotalsBefore(p.id, date) }
    val wToday by rememberFlow(p.id, date, initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotalsOn(p.id, date) }
    val pBefore by rememberFlow(p.id, "pb$date", initial = emptyList<CurrencyTotal>()) { repo.dao.purchaseTotalsBefore(p.id, date) }
    val pToday by rememberFlow(p.id, "pt$date", initial = emptyList<CurrencyTotal>()) { repo.dao.purchaseTotalsOn(p.id, date) }
    val editable = remember(sheet) { repo.sheetEditable(sheet) }

    val live = entries.filter { !it.voided }
    val carried = mergeMinus(mergeMinus(netBefore.toMap(), wBefore.toMap()), pBefore.toMap())
    val receipts = live.filter { it.kind == CashKind.IN }.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.amount } }
    val cashOut = live.filter { it.kind == CashKind.OUT }.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.amount } }
    val payments = mergePlus(mergePlus(cashOut, wToday.toMap()), pToday.toMap())
    val codes = (carried.keys + receipts.keys + payments.keys).filter {
        (carried[it] ?: 0L) != 0L || (receipts[it] ?: 0L) != 0L || (payments[it] ?: 0L) != 0L
    }.sorted()

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DateNavigator(date, onDate) }
        item { LockStatus(editable, sheet?.createdAt) }
        item {
            Button(onClick = { nav.navigate(Routes.attendance(p.id, date)) }, modifier = Modifier.fillMaxWidth()) {
                Text("فتح حركة هذا اليوم كاملة (العمال + الصندوق)")
            }
        }
        if (sheet != null) item {
            Text("كشف حركة الصندوق رقم ${sheet!!.serial}", style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionCard("كشف حركة الصندوق اليومية (A2-F)") {
                if (codes.isEmpty()) Text("لا توجد حركة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                codes.forEach { c ->
                    val cName = currencies.firstOrNull { it.code == c }?.name ?: c
                    val current = (carried[c] ?: 0L) + (receipts[c] ?: 0L) - (payments[c] ?: 0L)
                    Text(cName, fontWeight = FontWeight.Bold, color = Brand)
                    KeyValue("الرصيد المرحّل من اليوم السابق", money(carried[c] ?: 0L, c, currencies))
                    KeyValue("+ المقبوضات", money(receipts[c] ?: 0L, c, currencies), Good)
                    KeyValue("− المدفوعات", money(payments[c] ?: 0L, c, currencies), Bad)
                    KeyValue("   منها مسحوبات العمال", money(wToday.toMap()[c] ?: 0L, c, currencies))
                    KeyValue("   منها مشتريات المواد", money(pToday.toMap()[c] ?: 0L, c, currencies))
                    KeyValue("= الرصيد الحالي بالصندوق", money(current, c, currencies), if (current < 0) Bad else Good, bold = true)
                    HorizontalDivider()
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { nav.navigate(Routes.cashForm(p.id, date, 0, CashKind.IN)) },
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Good)
                ) { Icon(Icons.Filled.Add, null); Text(" قبض") }
                Button(
                    onClick = { nav.navigate(Routes.cashForm(p.id, date, 0, CashKind.OUT)) },
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Bad)
                ) { Icon(Icons.Filled.Remove, null); Text(" صرف") }
            }
        }
        if (entries.isEmpty()) item { EmptyState("لا توجد قيود في هذا اليوم") }
        items(entries, key = { it.id }) { e ->
            SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.cashForm(p.id, date, e.id, e.kind)) }) {
                val deco = if (e.voided) TextDecoration.LineThrough else TextDecoration.None
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${if (e.kind == CashKind.IN) "قبض" else "صرف"} • ${e.category}" + if (e.voided) "  (ملغى)" else "",
                            fontWeight = FontWeight.Bold, textDecoration = deco
                        )
                        if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall)
                        if (e.docType.isNotBlank() || e.docNo.isNotBlank())
                            Text("${e.docType} ${e.docNo}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        money(e.amount, e.currency, currencies),
                        color = if (e.kind == CashKind.IN) Good else Bad,
                        fontWeight = FontWeight.Bold, textDecoration = deco
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReportsTab(p: Project, currencies: List<CurrencyEntity>) {
    val repo = Graph.repo
    val due by rememberFlow(p.id, initial = emptyList<CurrencyTotal>()) { repo.dao.dueTotals(p.id) }
    val accW by rememberFlow(p.id, "accw", initial = emptyList<CurrencyTotal>()) { repo.dao.accountWithdrawTotals(p.id) }
    val withdrawn by rememberFlow(p.id, initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotals(p.id) }
    val outCats by rememberFlow(p.id, CashKind.OUT, initial = emptyList<CategoryTotal>()) { repo.dao.categoryTotals(p.id, CashKind.OUT) }
    val inCats by rememberFlow(p.id, CashKind.IN, initial = emptyList<CategoryTotal>()) { repo.dao.categoryTotals(p.id, CashKind.IN) }
    val purchases by rememberFlow(p.id, "p", initial = emptyList<CurrencyTotal>()) { repo.dao.purchaseTotals(p.id) }
    val pu = purchases.toMap()

    val receipts = inCats.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.total } }
    val expenses = outCats.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.total } }
    val w = withdrawn.toMap()
    val codes = (receipts.keys + expenses.keys + w.keys + pu.keys).sorted()

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ReportsExportCard(p) }
        item {
            SectionCard("النشاط المالي التراكمي للمشروع") {
                if (codes.isEmpty()) Text("لا توجد حركة بعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
                codes.forEach { c ->
                    val r = receipts[c] ?: 0L
                    val ex = expenses[c] ?: 0L
                    val wd = w[c] ?: 0L
                    Text(currencies.firstOrNull { it.code == c }?.name ?: c, fontWeight = FontWeight.Bold, color = Brand)
                    KeyValue("إجمالي المقبوضات", money(r, c, currencies), Good)
                    val pv = pu[c] ?: 0L
                    KeyValue("مسحوبات العمال", money(wd, c, currencies))
                    KeyValue("قيمة المواد المشتراة", money(pv, c, currencies))
                    KeyValue("المصروفات الأخرى", money(ex, c, currencies))
                    KeyValue("إجمالي المدفوعات", money(wd + pv + ex, c, currencies), Bad)
                    KeyValue("الرصيد الباقي في الصندوق", money(r - wd - pv - ex, c, currencies), bold = true)
                    HorizontalDivider()
                }
            }
        }
        item {
            SectionCard("مستحقات العمال (بعملة حساب كل عامل)") {
                val d = due.toMap()
                val w = accW.toMap()
                val keys = (d.keys + w.keys).sorted()
                if (keys.isEmpty()) Text("لا توجد بيانات", color = MaterialTheme.colorScheme.onSurfaceVariant)
                keys.forEach { c ->
                    KeyValue("المستحق ($c)", money(d[c] ?: 0L, c, currencies))
                    KeyValue("المسحوب ($c)", money(w[c] ?: 0L, c, currencies))
                    HorizontalDivider()
                }
                Text(
                    "المسحوب يشمل دفعات عمال المقاولة. تفاصيل كل عامل في تبويب العمال.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            SectionCard("المصروفات الأخرى حسب البند (B2-F)") {
                if (outCats.isEmpty()) Text("لا توجد مصروفات", color = MaterialTheme.colorScheme.onSurfaceVariant)
                outCats.forEach { KeyValue(it.category, money(it.total, it.currency, currencies)) }
            }
        }
        item {
            SectionCard("المقبوضات حسب المصدر") {
                if (inCats.isEmpty()) Text("لا توجد مقبوضات", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inCats.forEach { KeyValue(it.category, money(it.total, it.currency, currencies)) }
            }
        }
    }
}
