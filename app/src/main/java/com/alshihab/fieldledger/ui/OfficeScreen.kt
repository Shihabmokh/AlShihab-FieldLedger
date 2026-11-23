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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.WageRate
import com.alshihab.fieldledger.security.OfficePin
import com.alshihab.fieldledger.security.OfficePolicy
import com.alshihab.fieldledger.util.Money
import com.alshihab.fieldledger.util.normalizeDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Office-only settings behind a separate office password: wage list and whether field staff may type wages. */
@Composable
fun OfficeScreen(nav: NavController) {
    var unlocked by remember { mutableStateOf(false) }
    Scaffold(topBar = { AppBar("إعدادات المكتب", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            if (!unlocked) OfficeGate { unlocked = true } else OfficeContent()
        }
    }
}

@Composable
private fun OfficeGate(onOk: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { !OfficePin.isSet(ctx) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (setup) "أنشئ كلمة مرور المكتب. هذه الكلمة تختلف عن رمز فتح التطبيق، وتبقى عند المكتب فقط، ولا تُعطى للمحاسب الميداني."
            else "أدخل كلمة مرور المكتب",
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            p1, { p1 = normalizeDigits(it).filter { c -> c.isDigit() }.take(OfficePin.MAX_LEN); error = null },
            label = { Text("كلمة مرور المكتب (أرقام)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (setup) OutlinedTextField(
            p2, { p2 = normalizeDigits(it).filter { c -> c.isDigit() }.take(OfficePin.MAX_LEN); error = null },
            label = { Text("تأكيد كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = Bad) }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
            if (p1.length < OfficePin.MIN_LEN) { error = "4 أرقام على الأقل"; return@Button }
            if (setup && p1 != p2) { error = "غير متطابقتين"; return@Button }
            busy = true
            scope.launch {
                if (setup) {
                    withContext(Dispatchers.Default) { OfficePin.setPin(ctx, p1) }
                    Graph.repo.logEvent("أمان", "تم إنشاء كلمة مرور المكتب")
                    onOk()
                } else {
                    val wait = OfficePin.lockoutRemaining(ctx)
                    val ok = wait == 0L && withContext(Dispatchers.Default) { OfficePin.verify(ctx, p1) }
                    if (ok) onOk() else {
                        Graph.repo.logEvent("تنبيه أمني", "محاولة خاطئة لدخول إعدادات المكتب")
                        val w = OfficePin.lockoutRemaining(ctx)
                        error = if (w > 0) "محاولات كثيرة. انتظر ${w / 1000 + 1} ثانية" else "كلمة المرور غير صحيحة"
                        p1 = ""
                    }
                }
                busy = false
            }
        }) { Text(if (setup) "إنشاء" else "دخول") }
    }
}

@Composable
private fun OfficeContent() {
    val ctx = LocalContext.current
    val repo = Graph.repo
    val rates by rememberFlow("rates", initial = emptyList<WageRate>()) { repo.dao.wageRates() }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    var manual by remember { mutableStateOf(OfficePolicy.allowManualWage(ctx)) }
    var editing by remember { mutableStateOf<WageRate?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("صلاحيات المحاسب الميداني") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("السماح بإدخال الأجر اليومي يدوياً")
                        Text(
                            if (manual) "المحاسب يستطيع كتابة الأجر أو الاختيار من القائمة" else "المحاسب يختار من قائمة المكتب فقط",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = manual, onCheckedChange = {
                        manual = it
                        OfficePolicy.setAllowManualWage(ctx, it)
                        Graph.scope.launch { repo.logEvent("إعدادات المكتب", if (it) "سُمح بالأجر اليدوي" else "مُنع الأجر اليدوي") }
                    })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("قائمة الأجور اليومية", fontWeight = FontWeight.Bold, color = Brand, modifier = Modifier.weight(1f))
                Button(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Text(" أجر جديد") }
            }
        }
        item {
            Text(
                "تعديل أجر في القائمة يُطبَّق على كل العمال المرتبطين به من اليوم فصاعداً، ولا يغيّر الأيام المحفوظة سابقاً.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (rates.isEmpty()) item { EmptyState("لا توجد أجور بعد. أضف مثلاً: عامل، معلم بناء، سباك، كهربائي…") }
        items(rates, key = { it.id }) { r ->
            SectionCard(modifier = Modifier.clickable { editing = r }) {
                Row {
                    Text(r.title + if (!r.active) "  (موقوف)" else "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(money(r.dailyWage, r.currency, currencies), color = Brand, fontWeight = FontWeight.Bold)
                }
                if (r.overtimeRate > 0) Text("الساعة الإضافية: ${money(r.overtimeRate, r.currency, currencies)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (adding) WageRateDialog(null, currencies) { adding = false }
    editing?.let { r -> WageRateDialog(r, currencies) { editing = null } }
}

@Composable
private fun WageRateDialog(existing: WageRate?, currencies: List<CurrencyEntity>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var currency by remember { mutableStateOf(existing?.currency ?: "YER") }
    var wage by remember { mutableStateOf(existing?.let { Money.toInput(it.dailyWage) } ?: "") }
    var ot by remember { mutableStateOf(existing?.let { Money.toInput(it.overtimeRate) } ?: "") }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "أجر جديد" else "تعديل الأجر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("المهنة / الوصف *", title, { title = it; error = null })
                DropdownField(
                    "العملة", currencies, currencies.firstOrNull { it.code == currency },
                    { currencyLabel(it) }, { currency = it.code }, enabled = existing == null
                )
                MoneyField("الأجر اليومي *", wage, { wage = it }, currency, currencies)
                MoneyField("سعر الساعة الإضافية", ot, { ot = it }, currency, currencies, showWords = false)
                if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مستخدم", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = Money.parse(wage)
                val o = Money.parse(ot)
                when {
                    title.isBlank() -> { error = "أدخل الوصف"; return@TextButton }
                    w == null || w <= 0 -> { error = "أدخل الأجر"; return@TextButton }
                    o == null -> { error = "سعر الساعة غير صحيح"; return@TextButton }
                    else -> {}
                }
                val r = (existing ?: WageRate(title = "", currency = currency, dailyWage = 0)).copy(
                    title = title.trim(), currency = currency, dailyWage = w ?: 0L, overtimeRate = o ?: 0L, active = active
                )
                scope.launch {
                    Graph.repo.saveWageRate(r)
                    Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("إلغاء") } }
    )
}
