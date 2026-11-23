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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.security.DeviceIntegrity
import com.alshihab.fieldledger.util.Dates
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = Graph.repo
    val projects by rememberFlow(Unit, initial = emptyList<Project>()) { repo.dao.projects() }
    val rooted = remember { DeviceIntegrity.isLikelyRooted() }
    val official = remember { DeviceIntegrity.isOfficialBuild(ctx) }

    Scaffold(
        topBar = {
            AppBar("المشاريع", actions = {
                IconButton(onClick = { nav.navigate(Routes.WORKERS) }) {
                    Icon(Icons.Filled.Groups, contentDescription = "سجل العمال")
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "الإعدادات")
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.projectForm(0)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("مشروع جديد") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (rooted) item { Banner("تحذير: الجهاز يبدو مكسور الحماية (Root). بيانات الدفتر قد تكون عرضة للخطر.", Bad) }
            if (!official) item { Banner("هذه النسخة ليست موقعة بمفتاح المكتب الرسمي.", Warn) }
            if (projects.isEmpty()) item {
                EmptyState("لا توجد مشاريع بعد.\nابدأ بإضافة مشروع جديد من الزر في الأسفل.")
            }
            items(projects, key = { it.id }) { p ->
                SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.project(p.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (!p.active) AssistChip(onClick = {}, label = { Text("مغلق") })
                    }
                    Text(
                        listOf(p.governorate, p.district, p.uzla, p.village).filter { it.isNotBlank() }.joinToString(" / "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (p.site.isNotBlank()) Text("موقع العمل: ${p.site}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
fun ProjectFormScreen(nav: NavController, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val currencies by rememberFlow(Unit, initial = emptyList()) { repo.dao.currencies() }

    var loaded by remember { mutableStateOf(id == 0L) }
    var original by remember { mutableStateOf<Project?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var governorate by rememberSaveable { mutableStateOf("") }
    var district by rememberSaveable { mutableStateOf("") }
    var uzla by rememberSaveable { mutableStateOf("") }
    var village by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf("") }
    var client by rememberSaveable { mutableStateOf("") }
    var contractNo by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf(Dates.today()) }
    var baseCurrency by rememberSaveable { mutableStateOf("YER") }
    var accountant by rememberSaveable { mutableStateOf("") }
    var supervisor by rememberSaveable { mutableStateOf("") }
    var engineer by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(true) }
    var nameError by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != 0L && !loaded) {
            repo.dao.project(id)?.let { p ->
                original = p
                name = p.name; governorate = p.governorate; district = p.district; uzla = p.uzla
                village = p.village; site = p.site; client = p.client; contractNo = p.contractNo
                startDate = p.startDate; baseCurrency = p.baseCurrency; accountant = p.accountantName
                supervisor = p.supervisorName; engineer = p.engineerName; active = p.active
            }
            loaded = true
        }
    }

    Scaffold(topBar = { AppBar(if (id == 0L) "مشروع جديد" else "بيانات المشروع", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard("المعلومات الأساسية") {
                Field("اسم المشروع *", name, { name = it; nameError = false }, isError = nameError,
                    supporting = if (nameError) "اسم المشروع مطلوب" else null)
                Field("المحافظة", governorate, { governorate = it })
                Field("المديرية", district, { district = it })
                Field("العزلة", uzla, { uzla = it })
                Field("القرية", village, { village = it })
                Field("موقع العمل", site, { site = it })
            }
            SectionCard("العقد") {
                Field("الجهة / العميل", client, { client = it })
                Field("رقم العقد", contractNo, { contractNo = it })
                Field("تاريخ البدء (yyyy-mm-dd)", startDate, { startDate = it })
                DropdownField(
                    "العملة الأساسية للمشروع",
                    currencies,
                    currencies.firstOrNull { it.code == baseCurrency },
                    { currencyLabel(it) },
                    { baseCurrency = it.code }
                )
            }
            SectionCard("المسؤولون (تظهر أسماؤهم في الكشوفات)") {
                Field("المحاسب الميداني", accountant, { accountant = it })
                Field("المشرف الميداني (الفني المقيم)", supervisor, { supervisor = it })
                Field("المهندس / مدير المشروع", engineer, { engineer = it })
            }
            if (id != 0L) SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("المشروع نشط", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                Text(
                    "لا يمكن حذف المشروع. عند انتهائه أوقفه هنا وسيبقى سجله كاملاً.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    val p = (original ?: Project(name = "")).copy(
                        id = id, name = name.trim(), governorate = governorate.trim(), district = district.trim(),
                        uzla = uzla.trim(), village = village.trim(), site = site.trim(), client = client.trim(),
                        contractNo = contractNo.trim(), startDate = startDate.trim(), baseCurrency = baseCurrency,
                        accountantName = accountant.trim(), supervisorName = supervisor.trim(),
                        engineerName = engineer.trim(), active = active
                    )
                    scope.launch {
                        val newId = repo.saveProject(p)
                        Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                        if (id == 0L) nav.navigate(Routes.project(newId))
                    }
                },
                enabled = loaded,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("حفظ") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
