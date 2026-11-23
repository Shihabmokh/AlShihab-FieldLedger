package com.alshihab.fieldledger.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.DuplicateException
import com.alshihab.fieldledger.data.ItemKind
import com.alshihab.fieldledger.report.ReportKind
import com.alshihab.fieldledger.data.Material
import com.alshihab.fieldledger.data.MaterialMove
import com.alshihab.fieldledger.data.MaterialPeriod
import com.alshihab.fieldledger.data.MoveType
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.data.StockQty
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.fmtNum
import com.alshihab.fieldledger.util.parseDecimal
import kotlinx.coroutines.launch

val COMMON_UNITS = listOf("كيس", "طن", "م³", "م²", "م.ط", "متر", "قطعة", "حبة", "لفة", "لتر", "برميل", "كجم", "جالون", "علبة", "رأس", "مجموعة")

// =====================================================================
// Store tab: catalogue + current stock
// =====================================================================
@Composable
fun StoreTab(nav: NavController, p: Project, currencies: List<CurrencyEntity>) {
    val repo = Graph.repo
    val allItems by rememberFlow(p.id, "m", initial = emptyList<Material>()) { repo.dao.materials(p.id) }
    var kind by rememberSaveable { mutableStateOf(ItemKind.MATERIAL) }
    val materials = allItems.filter { it.kind == kind }
    val isEq = kind == ItemKind.EQUIPMENT
    val stock by rememberFlow(p.id, "s", initial = emptyList<StockQty>()) { repo.dao.stock(p.id) }
    val qty = stock.associate { it.materialId to it.qty }
    var editing by remember { mutableStateOf<Material?>(null) }
    var adding by remember { mutableStateOf(false) }
    val low = materials.filter { it.active && it.minQty > 0 && (qty[it.id] ?: 0.0) <= it.minQty }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Choice(listOf(ItemKind.MATERIAL to "المواد", ItemKind.EQUIPMENT to "معدات العمل"), kind, { kind = it })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Text(if (isEq) " معدة جديدة" else " صنف جديد")
                }
                OutlinedButton(onClick = { nav.navigate(Routes.stockReport(p.id, kind)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.TableChart, null); Text(if (isEq) " كشف المعدات" else " كشف المخزن 20F")
                }
            }
        }
        item {
            Button(onClick = { nav.navigate(Routes.attendance(p.id, Dates.today())) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isEq) "تسجيل توريد / شراء / إرجاع معدات (حركة اليوم)" else "تسجيل شراء / توريد / صرف مواد (حركة اليوم)")
            }
        }
        if (low.isNotEmpty()) item {
            Banner("أصناف وصلت لحد التنبيه: " + low.joinToString("، ") { it.name })
        }
        if (materials.isEmpty()) item {
            EmptyState(
                if (isEq) "لا توجد معدات بعد.\nأضف معدات العمل (خلاطة، هزاز، مولد، سقالات…) ثم سجّل توريدها وإرجاعها من شاشة حركة اليوم."
                else "لا توجد أصناف بعد.\nأضف أصناف المواد (إسمنت، حديد، مواسير…) ثم سجّل حركتها من شاشة حركة اليوم."
            )
        }
        items(materials, key = { it.id }) { m ->
            val q = qty[m.id] ?: 0.0
            val isLow = m.minQty > 0 && q <= m.minQty
            SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.material(m.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (!m.active) "  (موقوف)" else "", fontWeight = FontWeight.Bold)
                        Text("الوحدة: ${m.unit}" + if (m.minQty > 0) " • حد التنبيه ${fmtNum(m.minQty)}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtNum(q), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = if (isLow) Bad else Brand)
                        Text(m.unit, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, contentDescription = "تعديل الصنف") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (adding) MaterialDialog(p.id, null, defaultKind = kind, onDone = { adding = false })
    editing?.let { m -> MaterialDialog(p.id, m, onDone = { editing = null }) }
}

/** Add or edit a catalogue item. [onDone] receives the saved id (or null if cancelled). */
@Composable
fun MaterialDialog(projectId: Long, existing: Material?, defaultKind: String = ItemKind.MATERIAL, onDone: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    var minQty by remember { mutableStateOf(existing?.minQty?.takeIf { it > 0 }?.let { fmtNum(it) } ?: "") }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var kind by remember { mutableStateOf(existing?.kind ?: defaultKind) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (existing == null) "صنف جديد" else "تعديل الصنف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(listOf(ItemKind.MATERIAL to "مواد", ItemKind.EQUIPMENT to "معدات"), kind, { kind = it }, enabled = existing == null)
                Field("اسم الصنف *", name, { name = it; error = null })
                Field("الوحدة *", unit, { unit = it; error = null })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    COMMON_UNITS.forEach { u ->
                        TextButton(onClick = { unit = u }) { Text(u) }
                    }
                }
                Field("حد التنبيه (اختياري)", minQty, { minQty = it }, keyboard = KeyboardType.Decimal)
                if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الصنف مستخدم", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mq = parseDecimal(minQty)
                when {
                    name.isBlank() -> { error = "أدخل اسم الصنف"; return@TextButton }
                    unit.isBlank() -> { error = "أدخل الوحدة"; return@TextButton }
                    mq == null || mq < 0 -> { error = "حد التنبيه غير صحيح"; return@TextButton }
                    else -> {}
                }
                val m = (existing ?: Material(projectId = projectId, name = "", unit = "")).copy(
                    name = name.trim(), unit = unit.trim(), minQty = mq ?: 0.0, active = active, kind = kind
                )
                scope.launch {
                    try {
                        val id = Graph.repo.saveMaterial(m)
                        Toast.makeText(ctx, "تم حفظ الصنف", Toast.LENGTH_SHORT).show()
                        onDone(id)
                    } catch (e: DuplicateException) {
                        error = e.message
                    }
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("إلغاء") } }
    )
}

// =====================================================================
// Item card: every movement of one material with running balance
// =====================================================================
@Composable
fun MaterialCardScreen(nav: NavController, materialId: Long) {
    val repo = Graph.repo
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val moves by rememberFlow(materialId, initial = emptyList<MaterialMove>()) { repo.dao.movesForMaterial(materialId) }
    var material by remember { mutableStateOf<Material?>(null) }
    androidx.compose.runtime.LaunchedEffect(materialId) { material = repo.dao.material(materialId) }
    val m = material
    val live = moves.filter { !it.voided }
    val inP = live.filter { it.type == MoveType.PURCHASE }.sumOf { it.qty }
    val inO = live.filter { it.type == MoveType.OFFICE }.sumOf { it.qty }
    val out = live.filter { it.type == MoveType.ISSUE }.sumOf { it.qty }
    val returned = live.filter { it.type == MoveType.RETURN }.sumOf { it.qty }
    val values = live.filter { it.type == MoveType.PURCHASE }.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.total } }

    Scaffold(topBar = { AppBar("بطاقة صنف: ${m?.name ?: ""}", onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                SectionCard("الخلاصة") {
                    KeyValue("الوحدة", m?.unit ?: "")
                    KeyValue("الوارد بالشراء", fmtNum(inP))
                    KeyValue("الوارد من المكتب", fmtNum(inO))
                    if (out > 0) KeyValue("المنصرف", fmtNum(out))
                    if (returned > 0 || m?.kind == ItemKind.EQUIPMENT) KeyValue("المرتجع للمكتب", fmtNum(returned))
                    KeyValue(if (m?.kind == ItemKind.EQUIPMENT) "الموجود في الموقع" else "الرصيد الحالي", fmtNum(inP + inO - out - returned), Brand, bold = true)
                    values.forEach { (c, v) -> KeyValue("قيمة المشتريات ($c)", money(v, c, currencies)) }
                }
            }
            if (moves.isEmpty()) item { EmptyState("لا توجد حركة لهذا الصنف") }
            var running = 0.0
            val withBal = moves.map { mv ->
                if (!mv.voided) running += if (MoveType.isOut(mv.type)) -mv.qty else mv.qty
                mv to running
            }
            items(withBal, key = { it.first.id }) { (mv, bal) ->
                val (label, color) = when (mv.type) {
                    MoveType.PURCHASE -> "شراء" to Good
                    MoveType.OFFICE -> "توريد من المكتب" to Accent
                    MoveType.RETURN -> "إرجاع للمكتب" to Warn
                    else -> "صرف" to Bad
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
                ) {
                    Row {
                        Text(
                            "$label • ${Dates.label(mv.date)}" + if (mv.voided) " (ملغى)" else "",
                            fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f),
                            textDecoration = if (mv.voided) TextDecoration.LineThrough else TextDecoration.None
                        )
                        Text((if (MoveType.isOut(mv.type)) "−" else "+") + fmtNum(mv.qty), fontWeight = FontWeight.Bold)
                    }
                    val info = when (mv.type) {
                        MoveType.PURCHASE -> listOf(mv.shop, if (mv.docNo.isNotBlank()) "فاتورة ${mv.docNo}" else "", money(mv.total, mv.currency, currencies))
                        MoveType.OFFICE, MoveType.RETURN -> listOf(if (mv.docNo.isNotBlank()) "سند ${mv.docNo}" else "", mv.person)
                        else -> listOf(mv.site, mv.person)
                    } + listOf(mv.details)
                    Text(info.filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                    KeyValue("الرصيد بعد الحركة", fmtNum(bal), bold = true)
                }
                HorizontalDivider()
            }
        }
    }
}

// =====================================================================
// 20F - store movement report for a week (Saturday → Friday) or any period
// =====================================================================
@Composable
fun StockReportScreen(nav: NavController, projectId: Long, kind: String = ItemKind.MATERIAL) {
    val isEq = kind == ItemKind.EQUIPMENT
    val repo = Graph.repo
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val allItems by rememberFlow(projectId, initial = emptyList<Material>()) { repo.dao.materials(projectId) }
    val materials = allItems.filter { it.kind == kind }
    var from by rememberSaveable { mutableStateOf(Dates.weekStart(Dates.today())) }
    var to by rememberSaveable { mutableStateOf(Dates.shift(Dates.weekStart(Dates.today()), 6)) }
    val rows by rememberFlow(projectId, "$from|$to", initial = emptyList<MaterialPeriod>()) { repo.dao.stockPeriod(projectId, from, to) }
    val byId = rows.associateBy { it.materialId }
    var fromText by remember(from) { mutableStateOf(from) }
    var toText by remember(to) { mutableStateOf(to) }

    Scaffold(topBar = {
        AppBar(if (isEq) "كشف توريد وإرجاع معدات العمل" else "كشف حركة المواد في المخزن (20F)", onBack = { nav.popBackStack() }, actions = {
            ExportButtons(projectId, if (isEq) ReportKind.EQUIPMENT else ReportKind.STOCK20, from, to)
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
                SectionCard("الفترة") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { from = Dates.shift(from, -7); to = Dates.shift(from, 6) }, modifier = Modifier.weight(1f)) {
                            Text("الأسبوع السابق")
                        }
                        OutlinedButton(onClick = {
                            from = Dates.weekStart(Dates.today()); to = Dates.shift(from, 6)
                        }, modifier = Modifier.weight(1f)) { Text("هذا الأسبوع") }
                        OutlinedButton(onClick = { from = Dates.shift(from, 7); to = Dates.shift(from, 6) }, modifier = Modifier.weight(1f)) {
                            Text("التالي")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(fromText, { fromText = it }, label = { Text("من") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(toText, { toText = it }, label = { Text("إلى") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        val ok = runCatching { java.time.LocalDate.parse(fromText); java.time.LocalDate.parse(toText) }.isSuccess
                        if (ok && fromText <= toText) { from = fromText; to = toText }
                    }) { Text("تطبيق الفترة (yyyy-mm-dd)") }
                    Text("من ${Dates.label(from)} إلى ${Dates.label(to)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (materials.isEmpty()) item { EmptyState("لا توجد أصناف") }
            items(materials, key = { it.id }) { m ->
                val r = byId[m.id]
                val opening = r?.opening ?: 0.0
                val incoming = (r?.purchased ?: 0.0) + (r?.office ?: 0.0)
                val issued = (r?.issued ?: 0.0) + (r?.returned ?: 0.0)
                SectionCard {
                    Text("${m.name} (${m.unit})", fontWeight = FontWeight.Bold, color = Brand)
                    Row(Modifier.fillMaxWidth()) {
                        StockCell(if (isEq) "السابق بالموقع" else "الرصيد السابق", opening)
                        StockCell(if (isEq) "المورد" else "الوارد", incoming)
                        StockCell(if (isEq) "المرتجع" else "المنصرف", issued)
                        StockCell(if (isEq) "الموجود بالموقع" else "المتبقي", opening + incoming - issued, bold = true)
                    }
                    if ((r?.office ?: 0.0) > 0 || (r?.purchased ?: 0.0) > 0) Text(
                        "منه بالشراء ${fmtNum(r?.purchased ?: 0.0)} • من المكتب ${fmtNum(r?.office ?: 0.0)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = currencies
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StockCell(label: String, v: Double, bold: Boolean = false) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Text(fmtNum(v), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = if (bold) Brand else MaterialTheme.colorScheme.onSurface)
    }
}

@Suppress("unused")
private fun spacerW() = Modifier.width(1.dp)
