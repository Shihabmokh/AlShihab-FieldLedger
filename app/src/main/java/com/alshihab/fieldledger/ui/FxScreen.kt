package com.alshihab.fieldledger.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.FxRate
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.fmtRate
import com.alshihab.fieldledger.util.parseDecimal
import kotlinx.coroutines.launch

/** Dated exchange rates: a new rate applies from its date on; earlier transactions keep the rate they were saved with. */
@Composable
fun FxScreen(nav: NavController) {
    val repo = Graph.repo
    val rates by rememberFlow(Unit, "fx", initial = emptyList<FxRate>()) { repo.dao.fxRates() }
    val currencies by rememberFlow(Unit, initial = emptyList<CurrencyEntity>()) { repo.dao.currencies() }
    var adding by remember { mutableStateOf(false) }
    val today = Dates.today()
    // current rate per pair = newest entry
    val current = rates.groupBy { listOf(it.fromCur, it.toCur).sorted() }.mapValues { e -> e.value.maxWith(compareBy({ it.date }, { it.id })) }

    Scaffold(
        topBar = { AppBar("أسعار الصرف", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("سعر جديد") })
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
                    "يُعتمد السعر الجديد من تاريخه للفترة القادمة، وتبقى كل معاملة سابقة بالسعر الذي حُفظت به. " +
                        "يُكتب السعر بالطريقة المعتادة: 1 دولار = 530 ريال.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (current.isNotEmpty()) item {
                SectionCard("الأسعار المعتمدة حالياً") {
                    current.values.sortedBy { it.fromCur }.forEach { r ->
                        KeyValue("1 ${r.fromCur} =", "${fmtRate(r.rate)} ${r.toCur}  (من ${r.date})", Brand, bold = true)
                    }
                }
            }
            item { Text("السجل", fontWeight = FontWeight.Bold, color = Brand) }
            if (rates.isEmpty()) item { EmptyState("لا توجد أسعار بعد") }
            items(rates, key = { it.id }) { r ->
                SectionCard {
                    Row {
                        Text("1 ${r.fromCur} = ${fmtRate(r.rate)} ${r.toCur}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("من ${Dates.label(r.date)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.date > today) Text("سيُعتمد في تاريخه", style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            }
        }
    }

    if (adding) FxDialog(currencies) { adding = false }
}

@Composable
private fun FxDialog(currencies: List<CurrencyEntity>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(Dates.today()) }
    var from by remember { mutableStateOf("USD") }
    var to by remember { mutableStateOf("YER") }
    var rate by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("سعر صرف جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("يُعتمد من تاريخ (yyyy-mm-dd)", date, { date = it; error = null })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("1 من", currencies, currencies.firstOrNull { it.code == from }, { it.code }, { from = it.code }, Modifier.weight(1f))
                    DropdownField("يساوي", currencies, currencies.firstOrNull { it.code == to }, { it.code }, { to = it.code }, Modifier.weight(1f))
                }
                Field("السعر", rate, { rate = it; error = null }, keyboard = KeyboardType.Decimal)
                val r = parseDecimal(rate)
                if (r != null && r > 0) Text("1 $from = ${fmtRate(r)} $to", fontWeight = FontWeight.Bold, color = Brand)
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = parseDecimal(rate)
                val okDate = runCatching { java.time.LocalDate.parse(date) }.isSuccess
                when {
                    !okDate -> { error = "التاريخ غير صحيح"; return@TextButton }
                    from == to -> { error = "اختر عملتين مختلفتين"; return@TextButton }
                    r == null || r <= 0.0 -> { error = "أدخل السعر"; return@TextButton }
                    else -> {}
                }
                scope.launch {
                    Graph.repo.addFx(FxRate(date = date, fromCur = from, toCur = to, rate = r ?: 0.0))
                    Toast.makeText(ctx, "تم حفظ السعر", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("إلغاء") } }
    )
}
