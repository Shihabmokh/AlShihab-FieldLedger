package com.alshihab.fieldledger.ui

import android.widget.Toast
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
            SectionCard {
                Field("الاسم الكامل *", name, { name = it; nameError = false }, isError = nameError,
                    supporting = if (nameError) "الاسم مطلوب" else null)
                Field("المهنة / الصفة", profession, { profession = it })
                Field("رقم الهاتف", phone, { phone = it }, keyboard = KeyboardType.Phone)
                Field("رقم الكرت", cardNo, { cardNo = it })
                Field("ملاحظات", notes, { notes = it }, singleLine = false)
            }
            Text(
                "الأجر ونوع العمل (يومية أو مقاولة) يُحددان عند إضافة العامل إلى مشروع، لأنهما قد يختلفان من مشروع لآخر.",
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
