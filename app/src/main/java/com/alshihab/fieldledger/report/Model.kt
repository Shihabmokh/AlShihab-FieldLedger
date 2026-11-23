package com.alshihab.fieldledger.report

import com.alshihab.fieldledger.data.Project
import java.security.MessageDigest

data class Col(val title: String, val weight: Float = 1f, val numeric: Boolean = false)

data class Section(
    val title: String?,
    val columns: List<Col>,
    val rows: List<List<String>>,
    val totals: List<List<String>> = emptyList(),
    /** short label/value lines printed under the table (e.g. carried balance, cumulative totals) */
    val notes: List<Pair<String, String>> = emptyList()
)

data class ReportDoc(
    val formNo: String,
    val title: String,
    val project: Project,
    val period: String,
    val sections: List<Section>,
    val extraHeader: List<Pair<String, String>> = emptyList(),
    val landscape: Boolean = false,
    val signatures: List<Pair<String, String>> = listOf(
        "المحاسب الميداني" to project.accountantName,
        "المشرف الميداني (الفني المقيم)" to project.supervisorName,
        "المهندس / مدير المشروع" to project.engineerName
    ),
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = ""
) {
    fun headerLines(): List<Pair<String, String>> = listOfNotNull(
        "اسم المشروع" to project.name,
        project.governorate.takeIf { it.isNotBlank() }?.let { "المحافظة" to it },
        project.district.takeIf { it.isNotBlank() }?.let { "المديرية" to it },
        project.uzla.takeIf { it.isNotBlank() }?.let { "العزلة" to it },
        project.village.takeIf { it.isNotBlank() }?.let { "القرية" to it },
        project.site.takeIf { it.isNotBlank() }?.let { "موقع العمل" to it },
        project.client.takeIf { it.isNotBlank() }?.let { "الجهة" to it },
        project.contractNo.takeIf { it.isNotBlank() }?.let { "رقم العقد" to it },
        "الفترة" to period
    ) + extraHeader

    /** SHA-256 over every value in the report: printed and encoded in the QR so a copy can be checked later. */
    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun add(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        add(formNo); add(title); add(project.id.toString()); add(project.name); add(period); add(exportedAt.toString())
        headerLines().forEach { add(it.first); add(it.second) }
        sections.forEach { sec ->
            add(sec.title ?: "")
            sec.columns.forEach { add(it.title) }
            (sec.rows + sec.totals).forEach { r -> r.forEach(::add) }
            sec.notes.forEach { add(it.first); add(it.second) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun qrText(hash: String, exportedText: String) =
        "AL-SHIHAB FIELD LEDGER\nنموذج: $formNo\nالمشروع: ${project.name}\nالفترة: $period\nالتصدير: $exportedText\nSHA256: $hash"
}
