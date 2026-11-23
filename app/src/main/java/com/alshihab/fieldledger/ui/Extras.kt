package com.alshihab.fieldledger.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alshihab.fieldledger.AppLock
import com.alshihab.fieldledger.Graph
import com.alshihab.fieldledger.data.Attachment
import com.alshihab.fieldledger.data.AssignmentWithWorker
import com.alshihab.fieldledger.data.Person
import com.alshihab.fieldledger.security.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attach invoice photos / PDFs to a saved entry. Files are encrypted at rest and can be opened, never deleted.
 * For an unsaved line [entityId] is 0 and the user is told to save first.
 */
@Composable
fun AttachmentsBar(projectId: Long, entity: String, entityId: Long, enabled: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (entityId == 0L) {
        Text("📎 يمكن إرفاق صورة الفاتورة أو المستند بعد حفظ هذا السطر", style = MaterialTheme.typography.bodySmall)
        return
    }
    val files by rememberFlow(entity, entityId, initial = emptyList<Attachment>()) { Graph.repo.dao.attachments(entity, entityId) }
    var busy by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun store(uri: Uri, fallback: String) {
        busy = true
        scope.launch {
            try {
                val st = withContext(Dispatchers.IO) { AttachmentStore.import(ctx, uri, fallback) }
                Graph.repo.addAttachment(
                    Attachment(
                        projectId = projectId, entity = entity, entityId = entityId, fileName = st.fileName,
                        mime = st.mime, size = st.size, sha256 = st.sha256, storedName = st.storedName
                    )
                )
                Toast.makeText(ctx, "تم إرفاق الملف", Toast.LENGTH_SHORT).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "تعذر إرفاق الملف", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store(uri, "مستند")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.exists()) store(AttachmentStore.uriFor(ctx, f), "صورة_${System.currentTimeMillis()}.jpg")
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (files.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { a ->
                AssistChip(
                    onClick = {
                        scope.launch {
                            try {
                                val intent = withContext(Dispatchers.IO) {
                                    AttachmentStore.openIntent(ctx, a.storedName, a.fileName, a.mime, a.sha256)
                                }
                                AppLock.allowExternal()
                                ctx.startActivity(Intent.createChooser(intent, a.fileName))
                            } catch (e: SecurityException) {
                                Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    label = { Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(if (a.mime == "application/pdf") Icons.Filled.Description else Icons.Filled.Image, contentDescription = null)
                    }
                )
            }
        }
        if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                val f = File(AttachmentStore.cameraDir(ctx), "cam_${System.currentTimeMillis()}.jpg")
                cameraFile = f
                AppLock.allowExternal()
                camera.launch(AttachmentStore.uriFor(ctx, f))
            }) { Icon(Icons.Filled.CameraAlt, null); Text(" تصوير") }
            OutlinedButton(enabled = !busy, onClick = {
                AppLock.allowExternal()
                picker.launch(arrayOf("image/*", "application/pdf"))
            }) { Icon(Icons.Filled.AttachFile, null); Text(" إرفاق ملف") }
        }
    }
}

/**
 * Pick a person: the project's workers, previously added people, or add a new person (name + phone,
 * optionally from the phone's contacts). The chosen name is returned as text.
 */
@Composable
fun PersonField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    projectId: Long,
    workers: List<AssignmentWithWorker>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    val persons by rememberFlow(projectId, "persons", initial = emptyList<Person>()) { Graph.repo.dao.persons(projectId) }
    var adding by remember { mutableStateOf(false) }
    val addLabel = "➕ إضافة شخص آخر…"
    val names = (workers.filter { it.a.active }.map { it.worker.name } + persons.map { it.name }).distinct()
    val options = names + addLabel
    DropdownField(
        label, options, value.ifBlank { null }, { it },
        { if (it == addLabel) adding = true else onChange(it) },
        modifier = modifier, enabled = enabled
    )
    if (adding) AddPersonDialog(projectId, onDone = { name -> adding = false; if (name != null) onChange(name) })
}

@Composable
private fun AddPersonDialog(projectId: Long, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0).orEmpty()
                    phone = c.getString(1).orEmpty().replace(" ", "").replace("-", "")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "تعذر قراءة جهة الاتصال", Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("إضافة شخص") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    AppLock.allowExternal()
                    contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }) { Icon(Icons.Filled.Contacts, null); Text("  من جهات الاتصال") }
                Field("الاسم *", name, { name = it; error = null })
                Field("رقم الهاتف", phone, { phone = it }, keyboard = KeyboardType.Phone)
                error?.let { Text(it, color = Bad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = "أدخل الاسم"; return@TextButton }
                scope.launch {
                    Graph.repo.addPerson(Person(projectId = projectId, name = name.trim(), phone = phone.trim()))
                    onDone(name.trim())
                }
            }) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("إلغاء") } }
    )
}

@Suppress("unused")
private val keepAlignment = Alignment.CenterVertically
