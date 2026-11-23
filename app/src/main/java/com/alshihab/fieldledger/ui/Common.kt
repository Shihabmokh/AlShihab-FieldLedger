package com.alshihab.fieldledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.alshihab.fieldledger.AppLock
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.CurrencyTotal
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money
import kotlinx.coroutines.flow.Flow

// Al-Shihab office logo palette
val Brand = Color(0xFF14228F)      // logo navy
val BrandDark = Color(0xFF0F196E)
val Accent = Color(0xFF490093)     // logo purple ("CONTRACTING")
val BrandGray = Color(0xFF9A9BAD)  // logo grey line
val Warn = Color(0xFFE08A00)
val Good = Color(0xFF2E7D32)
val Bad = Color(0xFFC62828)

private val colors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1F7),
    onPrimaryContainer = BrandDark,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDF7),
    onSecondaryContainer = Color(0xFF2B0057),
    tertiary = BrandGray,
    background = Color(0xFFF3F4FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6E8F2),
    outline = BrandGray,
    error = Bad
)

@Composable
fun AppRoot(activity: FragmentActivity) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    AppLock.checkIdle(ctx)
                }
            }
            Box(Modifier.fillMaxSize()) {
                AppNav()
                if (AppLock.locked) LockScreen(activity)
            }
        }
    }
}

/** Collect a Flow created once per key set (prevents re-subscribing on every recomposition). */
@Composable
fun <T> rememberFlow(key1: Any?, key2: Any? = null, initial: T, factory: () -> Flow<T>): State<T> {
    val flow = remember(key1, key2) { factory() }
    return flow.collectAsState(initial)
}

@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Column {
    TopAppBar(
        title = { Text(title, maxLines = 1, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Brand,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
    // thin purple + grey rule like the office letterhead
    Box(Modifier.fillMaxWidth().height(3.dp).background(Accent))
    Box(Modifier.fillMaxWidth().height(2.dp).background(BrandGray))
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let(display) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
fun Choice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                enabled = enabled
            )
        }
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand)
            content()
        }
    }
}

@Composable
fun Banner(text: String, color: Color = Warn, icon: ImageVector = Icons.Filled.Warning) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LockStatus(editable: Boolean, createdAt: Long?) {
    if (createdAt == null) return
    if (editable) {
        Banner(
            "الكشف مفتوح للتعديل - متبقي ${Dates.duration(com.alshihab.fieldledger.security.TimeGuard.remainingMs(createdAt))}",
            Good, Icons.Filled.LockOpen
        )
    } else {
        Banner("الكشف مقفل (مضى أكثر من 24 ساعة على إنشائه أو تم تغيير ساعة الجهاز)", Bad, Icons.Filled.Lock)
    }
}

@Composable
fun DateNavigator(date: String, onChange: (String) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onChange(Dates.shift(date, -1)) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "اليوم السابق")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Dates.label(date), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (date != Dates.today()) {
                    TextButton(onClick = { onChange(Dates.today()) }) { Text("العودة لليوم") }
                }
            }
            IconButton(
                onClick = { onChange(Dates.shift(date, 1)) },
                enabled = !Dates.isFuture(Dates.shift(date, 1))
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "اليوم التالي")
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = valueColor, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

// ---------- money helpers ----------
fun decimalsOf(code: String, currencies: List<CurrencyEntity>): Int =
    currencies.firstOrNull { it.code == code }?.decimals ?: 2

fun money(amount: Long, code: String, currencies: List<CurrencyEntity>): String =
    "${Money.fmt(amount, decimalsOf(code, currencies))} ${currencies.firstOrNull { it.code == code }?.symbol ?: code}"

fun List<CurrencyTotal>.toMap(): Map<String, Long> = associate { it.currency to it.total }

fun mergeMinus(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
    (a.keys + b.keys).associateWith { (a[it] ?: 0L) - (b[it] ?: 0L) }

fun mergePlus(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
    (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }

fun currencyLabel(c: CurrencyEntity) = "${c.name} (${c.code})"

val OUT_CATEGORIES = listOf("بترول", "ديزل", "تغذية", "صيانة", "مواصلات واتصالات", "تسهيلات", "مصاريف أخرى")
val IN_CATEGORIES = listOf("عهدة من المكتب", "تحويل", "مقبوضات أخرى")
val DOC_TYPES = listOf("سند قبض", "سند صرف", "فاتورة", "إشعار تحويل", "بدون مستند", "أخرى")

/** Shows 1250000.5 as 1,250,000.5 while the field keeps the raw number. */
object ThousandsTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val intPart = raw.substringBefore('.')
        val rest = raw.substring(intPart.length)
        val len = intPart.length
        val sb = StringBuilder()
        val o2t = IntArray(raw.length + 1)
        var t = 0
        for (i in 0 until len) {
            if (i > 0 && (len - i) % 3 == 0) { sb.append(','); t++ }
            o2t[i] = t
            sb.append(intPart[i]); t++
        }
        o2t[len] = t
        for (j in 1..rest.length) o2t[len + j] = t + j
        sb.append(rest)
        val out = sb.toString()
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = o2t[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int =
                out.substring(0, offset.coerceIn(0, out.length)).count { it != ',' }
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(out), mapping)
    }
}

/** Amount input: thousands separators while typing, Arabic digits accepted, amount in words below. */
@Composable
fun MoneyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    currencyCode: String,
    currencies: List<CurrencyEntity>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    hint: String? = null,
    showWords: Boolean = true
) {
    val minor = Money.parse(value) ?: 0L
    val cName = currencies.firstOrNull { it.code == currencyCode }?.name?.substringBefore(" (") ?: currencyCode
    val words = if (showWords && minor > 0) com.alshihab.fieldledger.util.Tafqeet.amount(minor, cName) else null
    val support = listOfNotNull(hint, words).joinToString("\n").ifBlank { null }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(com.alshihab.fieldledger.util.cleanAmountInput(it)) },
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        visualTransformation = ThousandsTransformation,
        supportingText = support?.let { s -> { Text(s, style = MaterialTheme.typography.bodySmall) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.titleMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
    )
}
