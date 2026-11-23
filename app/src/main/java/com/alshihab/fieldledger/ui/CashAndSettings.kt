package com.alshihab.fieldledger.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.BuildConfig
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.AuditLog
import com.alshihab.fieldledger.data.CashEntry
import com.alshihab.fieldledger.data.CashKind
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.CurrencyTotal
import com.alshihab.fieldledger.data.Forms
import com.alshihab.fieldledger.data.LockedException
import com.alshihab.fieldledger.data.Sheet
import com.alshihab.fieldledger.security.DeviceIntegrity
import com.alshihab.fieldledger.security.PinManager
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// Cash entry (A2-F receipts/payments, B2-F other expenses)
// =====================================================================
@Composable
fun CashFormScreen(nav: NavController, projectId: Long, date: String, entryId: Long, kindInit: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val isNew = entryId == 0L
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    val sheet by rememberFlow<Sheet?>(projectId, date, initial = null) { repo.dao.sheetFlow(projectId, Forms.CASH, date) }
    val cashNet by rememberFlow(projectId, initial = emptyList<CurrencyTotal>()) { repo.dao.cashNet(projectId) }
    val withdrawn by rememberFlow(projectId, initial = emptyList<CurrencyTotal>()) { repo.dao.withdrawTotals(projectId) }

    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<CashEntry?>(null) }
    var kind by rememberSaveable { mutableStateOf(kindInit) }
    var category by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("YER") }
    var amount by rememberSaveable { mutableStateOf("") }
    var docType by rememberSaveable { mutableStateOf("") }
    var docNo by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmOverdraw by remember { mutableStateOf(false) }
    var confirmVoid by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (loaded) return@LaunchedEffect
        if (isNew) {
            repo.dao.project(projectId)?.let { currency = it.baseCurrency }
            category = if (kind == CashKind.IN) IN_CATEGORIES.first() else OUT_CATEGORIES.first()
            docType = if (kind == CashKind.IN) "سند قبض" else "سند صرف"
        } else {
            repo.dao.cashEntry(entryId)?.let { e ->
                original = e
                kind = e.kind; category = e.category; currency = e.currency
                amount = Money.toInput(e.amount); docType = e.docType; docNo = e.docNo; description = e.description
            }
        }
        loaded = true
    }

    val editable = remember(sheet) { repo.sheetEditable(sheet) } && loaded && original?.voided != true
    val balance = mergeMinus(cashNet.toMap(), withdrawn.toMap())[currency] ?: 0L

    fun doSave() {
        val amt = Money.parse(amount) ?: return
        val e = (original ?: CashEntry(sheetId = 0, projectId = projectId, date = date, kind = kind, category = category, currency = currency, amount = amt))
            .copy(
                kind = kind, category = category, currency = currency, amount = amt,
                docType = docType, docNo = docNo.trim(), description = description.trim()
            )
        scope.launch {
            try {
                repo.saveCash(e)
                Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                nav.popBackStack()
            } catch (ex: LockedException) {
                error = ex.message
            }
        }
    }

    Scaffold(topBar = {
        AppBar(
            (if (kind == CashKind.IN) "قيد قبض" else "قيد صرف") + " - " + date,
            onBack = { nav.popBackStack() }
        )
    }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (original?.voided == true) Banner("هذا القيد ملغى", Bad)
            if (!isNew) LockStatus(editable, sheet?.createdAt)
            SectionCard {
                if (isNew) Choice(
                    listOf(CashKind.IN to "قبض (وارد للصندوق)", CashKind.OUT to "صرف (مصروف)"), kind,
                    {
                        kind = it
                        category = if (it == CashKind.IN) IN_CATEGORIES.first() else OUT_CATEGORIES.first()
                        docType = if (it == CashKind.IN) "سند قبض" else "سند صرف"
                    }
                )
                DropdownField(
                    if (kind == CashKind.IN) "مصدر المبلغ" else "بند المصروف",
                    if (kind == CashKind.IN) IN_CATEGORIES else OUT_CATEGORIES,
                    category, { it }, { category = it }, enabled = editable
                )
                DropdownField(
                    "العملة", currencies, currencies.firstOrNull { it.code == currency },
                    { currencyLabel(it) }, { currency = it.code }, enabled = editable
                )
                MoneyField(
                    "المبلغ *", amount, { amount = it; error = null }, currency, currencies, enabled = editable,
                    hint = if (kind == CashKind.OUT) "رصيد الصندوق الحالي: ${money(balance, currency, currencies)}" else null
                )
                DropdownField("نوع المستند", DOC_TYPES, docType, { it }, { docType = it }, enabled = editable)
                Field("رقم المستند", docNo, { docNo = it }, enabled = editable)
                Field("البيان / التفاصيل", description, { description = it }, enabled = editable, singleLine = false)
            }
            error?.let { Banner(it, Bad) }
            Button(
                enabled = editable,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val amt = Money.parse(amount)
                    if (amt == null || amt <= 0) { error = "أدخل مبلغاً صحيحاً"; return@Button }
                    if (description.isBlank()) { error = "اكتب البيان"; return@Button }
                    val available = balance + (if (original?.kind == CashKind.OUT && original?.currency == currency) original!!.amount else 0L)
                    if (kind == CashKind.OUT && amt > available) confirmOverdraw = true else doSave()
                }
            ) { Text("حفظ") }
            if (!isNew && editable) OutlinedButton(
                onClick = { confirmVoid = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)
            ) { Text("إلغاء هذا القيد") }
            Text(
                "لا يمكن حذف القيود. خلال 24 ساعة من إنشاء كشف اليوم يمكن تعديل القيد أو إلغاؤه، ويبقى أثره في سجل التدقيق.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (confirmOverdraw) AlertDialog(
        onDismissRequest = { confirmOverdraw = false },
        title = { Text("تجاوز رصيد الصندوق") },
        text = { Text("المبلغ أكبر من رصيد الصندوق الحالي (${money(balance, currency, currencies)}). هل تريد المتابعة؟") },
        confirmButton = { TextButton(onClick = { confirmOverdraw = false; doSave() }) { Text("متابعة") } },
        dismissButton = { TextButton(onClick = { confirmOverdraw = false }) { Text("رجوع") } }
    )

    if (confirmVoid) AlertDialog(
        onDismissRequest = { confirmVoid = false },
        title = { Text("إلغاء القيد") },
        text = { Text("سيُعلَّم القيد كملغى ولن يدخل في الحسابات، لكنه يبقى ظاهراً في الكشف وسجل التدقيق. متابعة؟") },
        confirmButton = {
            TextButton(onClick = {
                confirmVoid = false
                val e = original ?: return@TextButton
                scope.launch {
                    try {
                        repo.voidCash(e)
                        Toast.makeText(ctx, "تم إلغاء القيد", Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                    } catch (ex: LockedException) {
                        error = ex.message
                    }
                }
            }) { Text("إلغاء القيد", color = Bad) }
        },
        dismissButton = { TextButton(onClick = { confirmVoid = false }) { Text("رجوع") } }
    )
}

// =====================================================================
// Settings, currencies, audit log
// =====================================================================
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    var bio by remember { mutableStateOf(PinManager.biometricEnabled(ctx)) }
    val bioAvailable = remember { Biometric.available(ctx) }
    var showPin by remember { mutableStateOf(false) }
    var lockTimeout by remember { mutableStateOf(com.alshihab.fieldledger.AppLock.timeoutMs(ctx)) }
    val rooted = remember { DeviceIntegrity.isLikelyRooted() }
    val official = remember { DeviceIntegrity.isOfficialBuild(ctx) }

    Scaffold(topBar = { AppBar("الإعدادات", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard("الأمان") {
                ListItem(
                    headlineContent = { Text("تغيير الرمز السري") },
                    leadingContent = { Icon(Icons.Filled.Password, null) },
                    modifier = Modifier.clickable { showPin = true }
                )
                ListItem(
                    headlineContent = { Text("الدخول بالبصمة") },
                    supportingContent = { Text(if (bioAvailable) "استخدم بصمة الجهاز لفتح الدفتر" else "غير متاح على هذا الجهاز") },
                    trailingContent = {
                        Switch(checked = bio && bioAvailable, enabled = bioAvailable, onCheckedChange = {
                            bio = it; PinManager.setBiometric(ctx, it)
                        })
                    }
                )
                DropdownField(
                    "القفل التلقائي بعد عدم الاستخدام",
                    com.alshihab.fieldledger.AppLock.TIMEOUT_OPTIONS,
                    com.alshihab.fieldledger.AppLock.TIMEOUT_OPTIONS.firstOrNull { it.first == lockTimeout },
                    { it.second },
                    { lockTimeout = it.first; com.alshihab.fieldledger.AppLock.setTimeout(ctx, it.first) }
                )
                Text(
                    "يُقفل الدفتر ويطلب الرمز أو البصمة إذا بقي مفتوحاً دون لمس، أو في الخلفية، أطول من هذه المدة.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                KeyValue("تشفير قاعدة البيانات", "مفعّل (AES-256)", Good)
                KeyValue("مفتاح التشفير", "محفوظ في مخزن مفاتيح الجهاز", Good)
                KeyValue("النسخ الاحتياطي السحابي", "معطّل", Good)
                KeyValue("حذف البيانات", "ممنوع على مستوى قاعدة البيانات", Good)
                KeyValue("سلامة الجهاز", if (rooted) "مكسور الحماية!" else "سليم", if (rooted) Bad else Good)
                KeyValue("توقيع التطبيق", if (official) "رسمي" else "غير رسمي", if (official) Good else Accent)
            }
            SectionCard("البيانات") {
                ListItem(
                    headlineContent = { Text("العملات") },
                    leadingContent = { Icon(Icons.Filled.CurrencyExchange, null) },
                    modifier = Modifier.clickable { nav.navigate(Routes.CURRENCIES) }
                )
                ListItem(
                    headlineContent = { Text("سجل التدقيق") },
                    supportingContent = { Text("كل عملية إنشاء أو تعديل أو إلغاء") },
                    leadingContent = { Icon(Icons.Filled.History, null) },
                    modifier = Modifier.clickable { nav.navigate(Routes.AUDIT) }
                )
            }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = Brand)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("الدفتر الميداني – الشهاب", fontWeight = FontWeight.Bold)
                        Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                        Text("مكتب الشهاب للمقاولات العامة", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showPin) ChangePinDialog { showPin = false }
}

@Composable
private fun ChangePinDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("تغيير الرمز السري") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("الرمز الحالي", old) { s: String -> old = s },
                    Triple("الرمز الجديد", new1) { s: String -> new1 = s },
                    Triple("تأكيد الرمز الجديد", new2) { s: String -> new2 = s }
                ).forEach { (label, value, set) ->
                    androidx.compose.material3.OutlinedTextField(
                        value = value,
                        onValueChange = { v -> set(com.alshihab.fieldledger.util.normalizeDigits(v).filter { it.isDigit() }.take(PinManager.MAX_LEN)) },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (new1.length < PinManager.MIN_LEN) { error = "الرمز الجديد قصير"; return@TextButton }
                if (new1 != new2) { error = "الرمزان غير متطابقين"; return@TextButton }
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { PinManager.verify(ctx, old) }
                    if (ok) {
                        withContext(Dispatchers.Default) { PinManager.setPin(ctx, new1) }
                        Graph.repo.logEvent("أمان", "تم تغيير الرمز السري")
                        Toast.makeText(ctx, "تم تغيير الرمز", Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        error = "الرمز الحالي غير صحيح"
                    }
                    busy = false
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("إلغاء") } }
    )
}

@Composable
fun CurrenciesScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppBar("العملات", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("عملة جديدة") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "كل عملة لها رصيد مستقل في صندوق المشروع. التقرير الموحّد بالعملة الأساسية (بسعر صرف تدخله) يُضاف مع التقارير.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(currencies, key = { it.code }) { c ->
                SectionCard {
                    KeyValue("${c.name} (${c.code})", "${c.symbol} • ${c.decimals} منازل عشرية")
                }
            }
        }
    }

    if (adding) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var symbol by remember { mutableStateOf("") }
        var decimals by remember { mutableStateOf("2") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("عملة جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("الرمز الدولي (مثل EUR)", code, { code = it.uppercase().filter { ch -> ch in 'A'..'Z' }.take(3) })
                    Field("الاسم", name, { name = it })
                    Field("الرمز المختصر (مثل €)", symbol, { symbol = it.take(5) })
                    Field("عدد المنازل العشرية", decimals, { decimals = it.take(1) }, keyboard = KeyboardType.Number)
                    error?.let { Text(it, color = Bad) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = com.alshihab.fieldledger.util.normalizeDigits(decimals).toIntOrNull()
                    val problem = when {
                        code.length != 3 -> "الرمز الدولي 3 أحرف"
                        name.isBlank() -> "أدخل الاسم"
                        d == null || d !in 0..2 -> "المنازل العشرية من 0 إلى 2"
                        currencies.any { it.code == code } -> "العملة موجودة"
                        else -> null
                    }
                    error = problem
                    if (problem == null && d != null) {
                        scope.launch {
                            repo.addCurrency(CurrencyEntity(code, name.trim(), symbol.ifBlank { code }, d))
                            Toast.makeText(ctx, "تمت الإضافة", Toast.LENGTH_SHORT).show()
                            adding = false
                        }
                    }
                }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun AuditScreen(nav: NavController) {
    val repo = Graph.repo
    val log by rememberFlow(Unit, initial = emptyList<AuditLog>()) { repo.dao.audit() }
    Scaffold(topBar = { AppBar("سجل التدقيق", onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (log.isEmpty()) item { EmptyState("السجل فارغ") }
            items(log, key = { it.id }) { a ->
                SectionCard {
                    Row {
                        Text("${a.action} • ${a.entity}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            color = if (a.action == "تنبيه أمني") Bad else Brand)
                        Text(Dates.dateTime(a.at), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(a.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
