package com.alshihab.fieldledger.ui

import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.OutlinedButton
import com.alshihab.fieldledger.AppLock
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.Worker
import kotlinx.coroutines.launch

/** Global worker registry, works like phone contacts: add and edit, never delete. */
@Composable
fun WorkersScreen(nav: NavController) {
    val repo = Graph.repo
    val workers by rememberFlow(Unit, initial = emptyList<Worker>()) { repo.dao.workers() }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = workers.filter {
        query.isBlank() || it.name.contains(query, true) || it.profession.contains(query, true) || it.phone.contains(query)
    }

    Scaffold(
        topBar = { AppBar("سجل العمال (${workers.size})", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.workerForm(0)) },
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text("عامل جديد") }
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
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("بحث بالاسم أو المهنة أو الرقم") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            if (shown.isEmpty()) item { EmptyState(if (workers.isEmpty()) "لا يوجد عمال بعد" else "لا توجد نتائج") }
            items(shown, key = { it.id }) { w ->
                SectionCard(
                    modifier = Modifier
                        .clickable { nav.navigate(Routes.workerForm(w.id)) }
                        .alpha(if (w.active) 1f else 0.55f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(w.profession, w.phone).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!w.active) AssistChip(onClick = {}, label = { Text("متوقف") })
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
fun WorkerFormScreen(nav: NavController, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    var loaded by remember { mutableStateOf(id == 0L) }
    var original by remember { mutableStateOf<Worker?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var profession by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var cardNo by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(true) }
    var nameError by remember { mutableStateOf(false) }
    val rates by rememberFlow("rates", initial = emptyList<com.alshihab.fieldledger.data.WageRate>()) { repo.dao.wageRates() }
    val manualAllowed = remember { com.alshihab.fieldledger.security.OfficePolicy.allowManualWage(ctx) }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0).orEmpty()
                    val ph = c.getString(1).orEmpty().replace(" ", "").replace("-", "")
                    if (name.isBlank()) name = n
                    phone = ph
                    nameError = false
                }
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "تعذر قراءة جهة الاتصال", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(id) {
        if (id != 0L && !loaded) {
            repo.dao.worker(id)?.let { w ->
                original = w
                name = w.name; profession = w.profession; phone = w.phone
                cardNo = w.cardNo; notes = w.notes; active = w.active
            }
            loaded = true
        }
    }

    Scaffold(topBar = { AppBar(if (id == 0L) "عامل جديد" else "بيانات العامل", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    AppLock.allowExternal()
                    contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Contacts, contentDescription = null)
                Text("  اختيار من جهات الاتصال")
            }
            SectionCard {
                Field("الاسم الكامل *", name, { name = it; nameError = false }, isError = nameError,
                    supporting = if (nameError) "الاسم مطلوب" else null)
                val titles = rates.filter { it.active }.map { it.title }.distinct()
                if (titles.isEmpty()) {
                    Field("المهنة / الصفة", profession, { profession = it })
                } else {
                    val other = "أخرى…"
                    val inList = profession in titles
                    DropdownField(
                        "المهنة (يحدد المكتب أجرها)",
                        if (manualAllowed) titles + other else titles,
                        if (inList) profession else if (profession.isNotBlank() && manualAllowed) other else null,
                        { it }, { profession = if (it == other) "" else it }
                    )
                    if (manualAllowed && !inList) Field("مهنة أخرى (يُدخل أجرها يدوياً)", profession, { profession = it })
                }
                Field("رقم الهاتف", phone, { phone = it }, keyboard = KeyboardType.Phone)
                Field("ملاحظات", notes, { notes = it }, singleLine = false)
            }
            Text(
                "الأجر اليومي يحدده المكتب حسب المهنة، ونوع العمل (يومية أو مقاولة) يُحدد عند إضافة العامل إلى مشروع، ورقم الكرت يُعطى تلقائياً بالتسلسل داخل كل مشروع.",
                style = MaterialTheme.typography.bodySmall
            )
            if (id != 0L) SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("العامل نشط", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                Text("لا يمكن حذف العامل. يمكن إيقافه فقط، ويبقى سجله في كل المشاريع.", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    val w = (original ?: Worker(name = "")).copy(
                        id = id, name = name.trim(), profession = profession.trim(), phone = phone.trim(),
                        cardNo = cardNo.trim(), notes = notes.trim(), active = active
                    )
                    scope.launch {
                        repo.saveWorker(w)
                        Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                    }
                },
                enabled = loaded,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("حفظ") }
        }
    }
}
