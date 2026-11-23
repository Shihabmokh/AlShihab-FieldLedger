package com.alshihab.fieldledger.report

import com.alshihab.fieldledger.data.Assignment
import com.alshihab.fieldledger.data.Attendance
import com.alshihab.fieldledger.data.CashKind
import com.alshihab.fieldledger.data.CurrencyEntity
import com.alshihab.fieldledger.data.ItemKind
import com.alshihab.fieldledger.data.LedgerDao
import com.alshihab.fieldledger.data.MoveType
import com.alshihab.fieldledger.data.PayType
import com.alshihab.fieldledger.data.Project
import com.alshihab.fieldledger.data.Status
import com.alshihab.fieldledger.data.WorkerCategory
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money
import com.alshihab.fieldledger.util.fmtNum

enum class ReportKind(val formNo: String, val title: String, val needsPeriod: Boolean = true) {
    F3("3F", "كشف المسحوبات اليومية للعمال"),
    F4("4F", "كشف الحركة اليومية للمواد والمعدات المشتراة"),
    CASH("A2-F", "كشف حركة الصندوق اليومية والمصروفات الأخرى"),
    F31("31F", "كشف الحركة اليومية للمواد المنصرفة من المخزن"),
    OFFICE("OF", "كشف المواد والمعدات الموردة من المكتب"),
    F5("5F", "تقرير بالنشاط المالي التراكمي للمشروع"),
    F7("7F", "التقرير الأسبوعي للمواد المشتراة"),
    F8("8F", "التقرير التحليلي للمصروفات الأخرى"),
    STOCK20("20F", "كشف حركة المواد في المخزن الموقعي"),
    EQUIPMENT("EQ", "كشف توريد وإرجاع معدات العمل"),
    T24("24T", "كشف مستحقات العمال الأساسيين"),
    T25("25T", "كشف صرف مستحقات العمال المؤقتين"),
    STATEMENT("WS", "كشف حساب عامل", false)
}

private val OUT_CATS = listOf("بترول", "ديزل", "تغذية", "صيانة", "مواصلات واتصالات", "تسهيلات", "مصاريف أخرى")

class ReportBuilder(private val dao: LedgerDao, private val appVersion: String) {
    private lateinit var cur: List<CurrencyEntity>

    private fun dec(code: String) = cur.firstOrNull { it.code == code }?.decimals ?: 2
    private fun amt(v: Long, code: String) = Money.fmt(v, dec(code))
    private fun cname(code: String) = cur.firstOrNull { it.code == code }?.name ?: code
    private fun period(from: String, to: String) =
        if (from == to) Dates.label(from) else "من ${Dates.label(from)} إلى ${Dates.label(to)}"

    private fun <K> addTo(m: MutableMap<K, Long>, k: K, v: Long) { m[k] = (m[k] ?: 0L) + v }

    suspend fun build(kind: ReportKind, projectId: Long, from: String, to: String, extraId: Long = 0): ReportDoc {
        cur = dao.currenciesList()
        val p = dao.project(projectId) ?: error("project not found")
        return when (kind) {
            ReportKind.F3 -> f3(p, from, to)
            ReportKind.F4 -> f4(p, from, to)
            ReportKind.CASH -> cash(p, from, to)
            ReportKind.F31 -> f31(p, from, to)
            ReportKind.OFFICE -> office(p, from, to)
            ReportKind.F5 -> f5(p, from, to)
            ReportKind.F7 -> f7(p, from, to)
            ReportKind.F8 -> f8(p, from, to)
            ReportKind.STOCK20 -> stock(p, from, to, ItemKind.MATERIAL)
            ReportKind.EQUIPMENT -> stock(p, from, to, ItemKind.EQUIPMENT)
            ReportKind.T24 -> dues(p, from, to, WorkerCategory.BASIC)
            ReportKind.T25 -> dues(p, from, to, WorkerCategory.TEMP)
            ReportKind.STATEMENT -> statement(p, extraId)
        }
    }

    private fun doc(kind: ReportKind, p: Project, from: String, to: String, sections: List<Section>, landscape: Boolean, extra: List<Pair<String, String>> = emptyList()) =
        ReportDoc(kind.formNo, kind.title, p, period(from, to), sections, extra, landscape, appVersion = appVersion)

    // ------------------------------------------------------------------ 3F
    private suspend fun f3(p: Project, from: String, to: String): ReportDoc {
        val asg = dao.assignmentsList(p.id).associateBy { it.a.id }
        val rows = dao.attendanceBetween(p.id, from, to).filter { it.status != Status.ABSENT || it.withdrawn != 0L || it.workNote.isNotBlank() }
        val multiDay = from != to
        val cols = buildList {
            add(Col("م", 0.4f)); if (multiDay) add(Col("التاريخ", 1f))
            add(Col("رقم الكرت", 0.5f)); add(Col("اسم العامل", 1.8f)); add(Col("المهنة", 1f)); add(Col("باليوم / مقاولة", 0.8f))
            add(Col("الحالة", 0.7f)); add(Col("المبلغ المستحق اليومي", 1f, true)); add(Col("المبلغ المسحوب", 1f, true))
            add(Col("المبلغ المتبقي", 1f, true)); add(Col("عملة الحساب", 0.6f)); add(Col("المدفوع فعلياً", 1.2f)); add(Col("بيان الأعمال المنفذة", 2f))
        }
        val due = mutableMapOf<String, Long>(); val wd = mutableMapOf<String, Long>()
        val data = rows.mapIndexed { i, r ->
            val aw = asg[r.assignmentId]
            val c = r.currency
            addTo(due, c, r.due); addTo(wd, c, r.withdrawn)
            val paid = if (r.payCurrency.isNotBlank() && r.payCurrency != c && r.payAmount != 0L)
                "${amt(r.payAmount, r.payCurrency)} ${r.payCurrency} × ${fmtNum(r.rate)}" else ""
            buildList {
                add("${i + 1}"); if (multiDay) add(r.date)
                add("${aw?.a?.cardNo ?: ""}"); add(aw?.worker?.name ?: ""); add(aw?.worker?.profession ?: "")
                add(if (aw?.a?.payType == PayType.CONTRACT) "مقاولة" else "يومية")
                add(statusLabel(r.status)); add(amt(r.due, c)); add(amt(r.withdrawn, c)); add(amt(r.due - r.withdrawn, c))
                add(c); add(paid); add(r.workNote)
            }
        }
        val before = dao.attendanceBefore(p.id, from)
        val bDue = mutableMapOf<String, Long>(); val bWd = mutableMapOf<String, Long>()
        before.forEach { addTo(bDue, it.currency, it.due); addTo(bWd, it.currency, it.withdrawn) }
        val codes = (due.keys + bDue.keys).sorted()
        val pad = if (multiDay) 7 else 6
        val totals = codes.filter { (due[it] ?: 0) != 0L || (wd[it] ?: 0) != 0L }.map { c ->
            List(pad) { if (it == 1) "إجمالي الحركة ($c)" else "" } + listOf(amt(due[c] ?: 0, c), amt(wd[c] ?: 0, c), amt((due[c] ?: 0) - (wd[c] ?: 0), c), c, "", "")
        }
        val notes = codes.flatMap { c ->
            listOf(
                "إجمالي المسحوب حتى اليوم السابق ($c)" to amt(bWd[c] ?: 0, c),
                "الإجمالي التراكمي للمسحوب ($c)" to amt((bWd[c] ?: 0) + (wd[c] ?: 0), c),
                "الإجمالي التراكمي للمستحق ($c)" to amt((bDue[c] ?: 0) + (due[c] ?: 0), c)
            )
        }
        return doc(ReportKind.F3, p, from, to, listOf(Section(null, cols, data, totals, notes)), true)
    }

    private fun statusLabel(s: String) = when (s) {
        Status.PRESENT -> "حاضر"
        Status.HALF -> "نصف يوم"
        Status.ABSENT -> "غائب"
        else -> "مقاولة"
    }

    private fun moveLabel(t: String) = when (t) {
        MoveType.PURCHASE -> "شراء"
        MoveType.OFFICE -> "توريد من المكتب"
        MoveType.RETURN -> "إرجاع للمكتب"
        else -> "صرف"
    }

    // ------------------------------------------------------------------ 4F
    private suspend fun f4(p: Project, from: String, to: String): ReportDoc {
        val mats = dao.materialsList(p.id).associateBy { it.id }
        val moves = dao.movesBetween(p.id, from, to).filter { it.type == MoveType.PURCHASE && !it.voided }
        val multiDay = from != to
        val cols = buildList {
            add(Col("م", 0.4f)); if (multiDay) add(Col("التاريخ", 1f))
            add(Col("النوع", 0.6f)); add(Col("اسم المادة / المعدة", 2f)); add(Col("الوحدة", 0.6f)); add(Col("الكمية", 0.7f, true))
            add(Col("سعر الوحدة", 1f, true)); add(Col("إجمالي القيمة", 1.1f, true)); add(Col("العملة", 0.6f))
            add(Col("اسم المحل", 1.3f)); add(Col("رقم الفاتورة", 0.8f))
        }
        val tot = mutableMapOf<String, Long>()
        val data = moves.mapIndexed { i, m ->
            val mt = mats[m.materialId]
            addTo(tot, m.currency, m.total)
            buildList {
                add("${i + 1}"); if (multiDay) add(m.date)
                add(if (mt?.kind == ItemKind.EQUIPMENT) "معدات" else "مواد"); add(mt?.name ?: ""); add(mt?.unit ?: "")
                add(fmtNum(m.qty)); add(amt(m.unitPrice, m.currency)); add(amt(m.total, m.currency)); add(m.currency)
                add(m.shop); add(m.docNo)
            }
        }
        val before = mutableMapOf<String, Long>()
        dao.movesBefore(p.id, from).filter { it.type == MoveType.PURCHASE }.forEach { addTo(before, it.currency, it.total) }
        val pad = if (multiDay) 7 else 6
        val totals = tot.keys.sorted().map { c -> List(pad) { if (it == 1) "إجمالي الحركة ($c)" else "" } + listOf(amt(tot[c] ?: 0, c), c, "", "") }
        val notes = (tot.keys + before.keys).sorted().flatMap { c ->
            listOf(
                "إجمالي الحركة حتى اليوم السابق ($c)" to amt(before[c] ?: 0, c),
                "الإجمالي التراكمي ($c)" to amt((before[c] ?: 0) + (tot[c] ?: 0), c)
            )
        }
        return doc(ReportKind.F4, p, from, to, listOf(Section(null, cols, data, totals, notes)), true)
    }

    // ------------------------------------------------------------------ A2-F / B2-F
    private suspend fun cash(p: Project, from: String, to: String): ReportDoc {
        val entries = dao.cashBetween(p.id, from, to).filter { !it.voided }
        val att = dao.attendanceBetween(p.id, from, to).filter { it.payAmount != 0L }
        val pur = dao.movesBetween(p.id, from, to).filter { it.type == MoveType.PURCHASE && !it.voided }
        val cols = listOf(
            Col("م", 0.4f), Col("التاريخ", 1f), Col("البيان", 2.2f), Col("المقبوضات", 1f, true), Col("المدفوعات", 1f, true),
            Col("العملة", 0.6f), Col("نوع المستند", 0.9f), Col("رقم المستند", 0.8f), Col("ملاحظات", 1.6f)
        )
        data class Line(val date: String, val text: String, val inA: Long, val outA: Long, val c: String, val docT: String, val docN: String, val note: String)
        val lines = mutableListOf<Line>()
        entries.forEach { e ->
            lines += Line(e.date, e.category, if (e.kind == CashKind.IN) e.amount else 0, if (e.kind == CashKind.OUT) e.amount else 0, e.currency, e.docType, e.docNo, e.description)
        }
        att.groupBy { it.date to it.payCurrency.ifBlank { it.currency } }.forEach { (k, list) ->
            lines += Line(k.first, "مسحوبات العمال (كشف 3F)", 0, list.sumOf { it.payAmount }, k.second, "كشف 3F", "", "${list.size} عامل")
        }
        pur.groupBy { it.date to it.currency }.forEach { (k, list) ->
            lines += Line(k.first, "مشتريات مواد / معدات (كشف 4F)", 0, list.sumOf { it.total }, k.second, "كشف 4F", "", "${list.size} سطر")
        }
        lines.sortBy { it.date }
        val inT = mutableMapOf<String, Long>(); val outT = mutableMapOf<String, Long>()
        val data = lines.mapIndexed { i, l ->
            addTo(inT, l.c, l.inA); addTo(outT, l.c, l.outA)
            listOf("${i + 1}", l.date, l.text, if (l.inA != 0L) amt(l.inA, l.c) else "", if (l.outA != 0L) amt(l.outA, l.c) else "", l.c, l.docT, l.docN, l.note)
        }
        // carried balance
        val carried = mutableMapOf<String, Long>()
        dao.cashBefore(p.id, from).forEach { addTo(carried, it.currency, if (it.kind == CashKind.IN) it.amount else -it.amount) }
        dao.attendanceBefore(p.id, from).forEach { if (it.payAmount != 0L) addTo(carried, it.payCurrency.ifBlank { it.currency }, -it.payAmount) }
        dao.movesBefore(p.id, from).filter { it.type == MoveType.PURCHASE }.forEach { addTo(carried, it.currency, -it.total) }
        val codes = (inT.keys + outT.keys + carried.keys).sorted()
        val totals = codes.filter { (inT[it] ?: 0) != 0L || (outT[it] ?: 0) != 0L }.map { c ->
            listOf("", "", "إجمالي الحركة ($c)", amt(inT[c] ?: 0, c), amt(outT[c] ?: 0, c), c, "", "", "")
        }
        val notes = codes.flatMap { c ->
            val cb = carried[c] ?: 0
            listOf(
                "الرصيد المرحّل من اليوم السابق ($c)" to amt(cb, c),
                "إجمالي المقبوضات + الرصيد المرحّل ($c)" to amt(cb + (inT[c] ?: 0), c),
                "الرصيد المتبقي الحالي بالصندوق ($c)" to amt(cb + (inT[c] ?: 0) - (outT[c] ?: 0), c)
            )
        }
        // B2-F detail
        val other = entries.filter { it.kind == CashKind.OUT }
        val bCols = listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("البند", 1.3f), Col("التفاصيل", 2.4f), Col("المبلغ", 1f, true), Col("العملة", 0.6f), Col("نوع المستند", 0.9f), Col("رقم المستند", 0.8f))
        val bt = mutableMapOf<String, Long>()
        val bRows = other.mapIndexed { i, e ->
            addTo(bt, e.currency, e.amount)
            listOf("${i + 1}", e.date, e.category, e.description, amt(e.amount, e.currency), e.currency, e.docType, e.docNo)
        }
        val bTot = bt.keys.sorted().map { c -> listOf("", "", "", "إجمالي المصروفات الأخرى ($c)", amt(bt[c] ?: 0, c), c, "", "") }
        return doc(
            ReportKind.CASH, p, from, to,
            listOf(
                Section("كشف حركة الصندوق اليومية (A2-F)", cols, data, totals, notes),
                Section("كشف حركة المصروفات الأخرى (B2-F)", bCols, bRows, bTot)
            ),
            true
        )
    }

    // ------------------------------------------------------------------ 31F
    private suspend fun f31(p: Project, from: String, to: String): ReportDoc {
        val mats = dao.materialsList(p.id).associateBy { it.id }
        val moves = dao.movesBetween(p.id, from, to).filter { it.type == MoveType.ISSUE && !it.voided }
        val cols = listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("اسم المادة", 1.8f), Col("الوحدة", 0.6f), Col("الكمية", 0.7f, true), Col("موقع العمل", 1.3f), Col("القائم بالعمل", 1.3f), Col("تفاصيل العمل", 2f))
        val data = moves.mapIndexed { i, m ->
            val mt = mats[m.materialId]
            listOf("${i + 1}", m.date, mt?.name ?: "", mt?.unit ?: "", fmtNum(m.qty), m.site, m.person, m.details)
        }
        return doc(ReportKind.F31, p, from, to, listOf(Section(null, cols, data)), false)
    }

    // ------------------------------------------------------------------ office supply
    private suspend fun office(p: Project, from: String, to: String): ReportDoc {
        val mats = dao.materialsList(p.id).associateBy { it.id }
        val moves = dao.movesBetween(p.id, from, to).filter { (it.type == MoveType.OFFICE || it.type == MoveType.RETURN) && !it.voided }
        val cols = listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("الحركة", 0.9f), Col("النوع", 0.6f), Col("الصنف / المعدة", 1.8f), Col("الوحدة", 0.6f), Col("الكمية", 0.7f, true), Col("رقم السند", 0.8f), Col("المستلم / المسلّم", 1.3f), Col("ملاحظات", 1.6f))
        val data = moves.mapIndexed { i, m ->
            val mt = mats[m.materialId]
            listOf(
                "${i + 1}", m.date, if (m.type == MoveType.OFFICE) "توريد للموقع" else "إرجاع للمكتب",
                if (mt?.kind == ItemKind.EQUIPMENT) "معدات" else "مواد", mt?.name ?: "", mt?.unit ?: "", fmtNum(m.qty), m.docNo, m.person, m.details
            )
        }
        return doc(ReportKind.OFFICE, p, from, to, listOf(Section(null, cols, data)), true)
    }

    // ------------------------------------------------------------------ 5F
    private suspend fun f5(p: Project, from: String, to: String): ReportDoc {
        val cashE = dao.cashBetween(p.id, from, to).filter { !it.voided }
        val att = dao.attendanceBetween(p.id, from, to).filter { it.payAmount != 0L }
        val pur = dao.movesBetween(p.id, from, to).filter { it.type == MoveType.PURCHASE && !it.voided }
        val carried = mutableMapOf<String, Long>()
        dao.cashBefore(p.id, from).forEach { addTo(carried, it.currency, if (it.kind == CashKind.IN) it.amount else -it.amount) }
        dao.attendanceBefore(p.id, from).forEach { if (it.payAmount != 0L) addTo(carried, it.payCurrency.ifBlank { it.currency }, -it.payAmount) }
        dao.movesBefore(p.id, from).filter { it.type == MoveType.PURCHASE }.forEach { addTo(carried, it.currency, -it.total) }
        val codes = (cashE.map { it.currency } + att.map { it.payCurrency.ifBlank { it.currency } } + pur.map { it.currency } + carried.keys).toSortedSet()
        val dates = (cashE.map { it.date } + att.map { it.date } + pur.map { it.date }).toSortedSet()
        val cols = listOf(
            Col("التاريخ", 1f), Col("اليوم", 0.8f), Col("المقبوضات اليومية", 1f, true), Col("العمال", 1f, true), Col("قيمة المواد المشتراة", 1f, true),
            Col("المصروفات الأخرى", 1f, true), Col("إجمالي المدفوعات اليومية", 1f, true), Col("الرصيد الباقي في صندوق المشروع اليومي", 1.1f, true),
            Col("الرصيد الباقي في صندوق المشروع التراكمي", 1.2f, true)
        )
        val sections = codes.map { c ->
            var cum = carried[c] ?: 0L
            var tIn = 0L; var tW = 0L; var tP = 0L; var tO = 0L
            val rows = dates.mapNotNull { d ->
                val inA = cashE.filter { it.date == d && it.currency == c && it.kind == CashKind.IN }.sumOf { it.amount }
                val oth = cashE.filter { it.date == d && it.currency == c && it.kind == CashKind.OUT }.sumOf { it.amount }
                val w = att.filter { it.date == d && it.payCurrency.ifBlank { it.currency } == c }.sumOf { it.payAmount }
                val pu = pur.filter { it.date == d && it.currency == c }.sumOf { it.total }
                if (inA == 0L && oth == 0L && w == 0L && pu == 0L) return@mapNotNull null
                val pay = w + pu + oth
                cum += inA - pay
                tIn += inA; tW += w; tP += pu; tO += oth
                listOf(d, Dates.dayName(d), amt(inA, c), amt(w, c), amt(pu, c), amt(oth, c), amt(pay, c), amt(inA - pay, c), amt(cum, c))
            }
            Section(
                "العملة: ${cname(c)} ($c)", cols, rows,
                listOf(listOf("الإجمالي", "", amt(tIn, c), amt(tW, c), amt(tP, c), amt(tO, c), amt(tW + tP + tO, c), amt(tIn - tW - tP - tO, c), amt(cum, c))),
                listOf("الرصيد المرحّل في بداية الفترة" to amt(carried[c] ?: 0, c), "الرصيد في نهاية الفترة" to amt(cum, c))
            )
        }
        return doc(ReportKind.F5, p, from, to, sections, true)
    }

    // ------------------------------------------------------------------ 7F
    private suspend fun f7(p: Project, from: String, to: String): ReportDoc {
        val mats = dao.materialsList(p.id).associateBy { it.id }
        val moves = dao.movesBetween(p.id, from, to).filter { it.type == MoveType.PURCHASE && !it.voided }
        val cols = listOf(Col("اليوم", 0.8f), Col("التاريخ", 1f), Col("الصنف", 1.8f), Col("الوحدة", 0.6f), Col("الكمية", 0.7f, true), Col("السعر", 1f, true), Col("القيمة", 1.1f, true), Col("العملة", 0.6f), Col("المحل", 1.2f))
        val tot = mutableMapOf<String, Long>()
        val rows = moves.map { m ->
            addTo(tot, m.currency, m.total)
            val mt = mats[m.materialId]
            listOf(Dates.dayName(m.date), m.date, mt?.name ?: "", mt?.unit ?: "", fmtNum(m.qty), amt(m.unitPrice, m.currency), amt(m.total, m.currency), m.currency, m.shop)
        }
        val before = mutableMapOf<String, Long>()
        dao.movesBefore(p.id, from).filter { it.type == MoveType.PURCHASE }.forEach { addTo(before, it.currency, it.total) }
        val totals = tot.keys.sorted().map { c -> listOf("إجمالي الحركة", "", "", "", "", "", amt(tot[c] ?: 0, c), c, "") }
        val notes = (tot.keys + before.keys).sorted().flatMap { c ->
            listOf("الرصيد السابق ($c)" to amt(before[c] ?: 0, c), "الإجمالي التراكمي ($c)" to amt((before[c] ?: 0) + (tot[c] ?: 0), c))
        }
        return doc(ReportKind.F7, p, from, to, listOf(Section(null, cols, rows, totals, notes)), true, listOf("الأسبوع" to weekLabel(from)))
    }

    private fun weekLabel(from: String): String {
        val d = java.time.LocalDate.parse(from)
        val week = (d.dayOfMonth - 1) / 7 + 1
        return "الأسبوع رقم ($week) من شهر ${d.monthValue} / ${d.year}"
    }

    // ------------------------------------------------------------------ 8F
    private suspend fun f8(p: Project, from: String, to: String): ReportDoc {
        val other = dao.cashBetween(p.id, from, to).filter { !it.voided && it.kind == CashKind.OUT }
        val before = dao.cashBefore(p.id, from).filter { it.kind == CashKind.OUT }
        val cols = listOf(Col("اليوم", 0.8f), Col("التاريخ", 1f)) + OUT_CATS.map { Col(it, 1f, true) } + Col("الإجمالي العام", 1.1f, true)
        val codes = (other.map { it.currency } + before.map { it.currency }).toSortedSet()
        val days = generateSequence(from) { Dates.shift(it, 1) }.takeWhile { it <= to }.toList()
        val sections = codes.map { c ->
            val catTot = LongArray(OUT_CATS.size)
            val rows = days.mapNotNull { d ->
                val dayE = other.filter { it.date == d && it.currency == c }
                if (dayE.isEmpty()) return@mapNotNull null
                val vals = OUT_CATS.mapIndexed { i, cat ->
                    val v = dayE.filter { (OUT_CATS.indexOf(it.category).takeIf { x -> x >= 0 } ?: OUT_CATS.lastIndex) == i }.sumOf { it.amount }
                    catTot[i] += v; v
                }
                listOf(Dates.dayName(d), d) + vals.map { amt(it, c) } + amt(vals.sum(), c)
            }
            val prev = before.filter { it.currency == c }.sumOf { it.amount }
            val total = catTot.sum()
            Section(
                "العملة: ${cname(c)} ($c)", cols, rows,
                listOf(listOf("إجمالي الحركة", "") + catTot.map { amt(it, c) } + amt(total, c)),
                listOf("الرصيد السابق" to amt(prev, c), "الإجمالي التراكمي" to amt(prev + total, c))
            )
        }
        return doc(ReportKind.F8, p, from, to, sections, true, listOf("الأسبوع" to weekLabel(from)))
    }

    // ------------------------------------------------------------------ 20F / equipment
    private suspend fun stock(p: Project, from: String, to: String, kind: String): ReportDoc {
        val isEq = kind == ItemKind.EQUIPMENT
        val mats = dao.materialsList(p.id).filter { it.kind == kind }
        val before = dao.movesBefore(p.id, from)
        val inP = dao.movesBetween(p.id, from, to).filter { !it.voided }
        val cols = if (isEq) listOf(
            Col("م", 0.4f), Col("اسم المعدة", 2f), Col("الوحدة", 0.6f), Col("الموجود سابقاً بالموقع", 1f, true),
            Col("المشتراة", 0.8f, true), Col("الموردة من المكتب", 0.9f, true), Col("المرتجعة للمكتب", 0.9f, true), Col("الموجود حالياً بالموقع", 1f, true)
        ) else listOf(
            Col("م", 0.4f), Col("اسم الصنف", 2f), Col("الوحدة", 0.6f), Col("الرصيد السابق", 0.9f, true), Col("الوارد بالشراء", 0.9f, true),
            Col("الوارد من المكتب", 0.9f, true), Col("الكميات المنصرفة", 0.9f, true), Col("الرصيد المتبقي", 0.9f, true), Col("قيمة المشتريات", 1.2f)
        )
        fun signed(t: String, q: Double) = if (MoveType.isOut(t)) -q else q
        val rows = mats.mapIndexed { i, m ->
            val op = before.filter { it.materialId == m.id }.sumOf { signed(it.type, it.qty) }
            val mm = inP.filter { it.materialId == m.id }
            val pu = mm.filter { it.type == MoveType.PURCHASE }.sumOf { it.qty }
            val of = mm.filter { it.type == MoveType.OFFICE }.sumOf { it.qty }
            val iss = mm.filter { it.type == MoveType.ISSUE }.sumOf { it.qty }
            val ret = mm.filter { it.type == MoveType.RETURN }.sumOf { it.qty }
            val rem = op + pu + of - iss - ret
            if (isEq) listOf("${i + 1}", m.name, m.unit, fmtNum(op), fmtNum(pu), fmtNum(of), fmtNum(ret), fmtNum(rem))
            else {
                val values = mm.filter { it.type == MoveType.PURCHASE }.groupBy { it.currency }
                    .map { (c, l) -> "${amt(l.sumOf { it.total }, c)} $c" }.joinToString(" + ")
                listOf("${i + 1}", m.name, m.unit, fmtNum(op), fmtNum(pu), fmtNum(of), fmtNum(iss + ret), fmtNum(rem), values)
            }
        }
        val sections = mutableListOf(Section(null, cols, rows))
        if (isEq) {
            val byId = mats.associateBy { it.id }
            val mv = inP.filter { byId.containsKey(it.materialId) }
            sections += Section(
                "تفاصيل حركة المعدات",
                listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("الحركة", 1f), Col("المعدة", 1.6f), Col("الكمية", 0.6f, true), Col("رقم السند / الفاتورة", 1f), Col("الشخص", 1.2f), Col("ملاحظات / الحالة", 1.8f)),
                mv.mapIndexed { i, m ->
                    listOf(
                        "${i + 1}", m.date,
                        moveLabel(m.type),
                        byId[m.materialId]?.name ?: "", fmtNum(m.qty), m.docNo, m.person, m.details
                    )
                }
            )
        }
        return doc(if (isEq) ReportKind.EQUIPMENT else ReportKind.STOCK20, p, from, to, sections, true)
    }

    // ------------------------------------------------------------------ 24T / 25T
    private suspend fun dues(p: Project, from: String, to: String, category: String): ReportDoc {
        val asg = dao.assignmentsList(p.id).filter { it.a.category == category }
        val att = dao.attendanceBetween(p.id, from, to).groupBy { it.assignmentId }
        val isTemp = category == WorkerCategory.TEMP
        val cols = listOf(
            Col("م", 0.35f), Col("رقم الكرت", 0.5f), Col("اسم العامل", 1.7f), Col("نوع العمل", 0.9f), Col("عدد أيام العمل", 0.6f, true),
            Col("عدد ساعات العمل الأساسي", 0.7f, true), Col("عدد ساعات العمل الإضافي", 0.7f, true), Col("المبلغ الأساسي المستحق", 1f, true),
            Col("المبلغ الإضافي المستحق", 0.9f, true), Col("إجمالي المبلغ المستحق", 1f, true), Col("إجمالي المبالغ المسحوبة بيد العامل", 1f, true),
            Col("صافي المبلغ المتبقي للعامل", 1f, true), Col("العملة", 0.5f),
            Col(if (isTemp) "توقيع أو بصمة العامل باستلام مستحقاته" else "مصادقة العامل بمستحقاته", 1.2f), Col("ملاحظات", 1f)
        )
        val tot = mutableMapOf<String, LongArray>()
        val rows = asg.mapNotNull { aw ->
            val rs = att[aw.a.id].orEmpty()
            if (rs.isEmpty() && !aw.a.active) return@mapNotNull null
            val a = aw.a
            val days = rs.sumOf { if (it.status == Status.PRESENT) 1.0 else if (it.status == Status.HALF) 0.5 else 0.0 }
            val ot = rs.sumOf { it.overtimeHours }
            val due = rs.sumOf { it.due }
            val otAmt = rs.sumOf { Math.round(it.overtimeHours * a.overtimeRate) }.coerceAtMost(due)
            val wd = rs.sumOf { it.withdrawn }
            val c = a.currency
            val t = tot.getOrPut(c) { LongArray(4) }
            t[0] += due - otAmt; t[1] += otAmt; t[2] += due; t[3] += wd
            val note = if (a.payType == PayType.CONTRACT) "مقاولة: ${amt(a.contractAmount, c)}" else ""
            listOf(
                "", "${a.cardNo}", aw.worker.name, aw.worker.profession, fmtNum(days), fmtNum(days * a.baseHours), fmtNum(ot),
                amt(due - otAmt, c), amt(otAmt, c), amt(due, c), amt(wd, c), amt(due - wd, c), c, "", note
            )
        }.mapIndexed { i, r -> listOf("${i + 1}") + r.drop(1) }
        val totals = tot.keys.sorted().map { c ->
            val t = tot.getValue(c)
            listOf("", "", "الإجمالي ($c)", "", "", "", "", amt(t[0], c), amt(t[1], c), amt(t[2], c), amt(t[3], c), amt(t[2] - t[3], c), c, "", "")
        }
        return doc(if (isTemp) ReportKind.T25 else ReportKind.T24, p, from, to, listOf(Section(null, cols, rows, totals)), true)
    }

    // ------------------------------------------------------------------ worker statement
    private suspend fun statement(p: Project, assignmentId: Long): ReportDoc {
        val aw = dao.assignmentsList(p.id).first { it.a.id == assignmentId }
        val a: Assignment = aw.a
        val rows: List<Attendance> = dao.attendanceForAssignmentList(assignmentId)
        val c = a.currency
        val contract = a.payType == PayType.CONTRACT
        val cols = listOf(Col("م", 0.4f), Col("التاريخ", 1f), Col("اليوم", 0.7f), Col("الحالة", 0.7f), Col("ساعات إضافية", 0.6f, true), Col("المستحق", 1f, true), Col("المسحوب", 1f, true), Col("المدفوع فعلياً", 1.3f), Col("الرصيد", 1f, true), Col("بيان الأعمال", 1.8f))
        var bal = if (contract) a.contractAmount else 0L
        val data = rows.mapIndexed { i, r ->
            bal += if (contract) -r.withdrawn else r.due - r.withdrawn
            val paid = if (r.payCurrency.isNotBlank() && r.payCurrency != c && r.payAmount != 0L) "${amt(r.payAmount, r.payCurrency)} ${r.payCurrency} × ${fmtNum(r.rate)}" else ""
            listOf("${i + 1}", r.date, Dates.dayName(r.date), statusLabel(r.status), if (r.overtimeHours > 0) fmtNum(r.overtimeHours) else "", amt(r.due, c), amt(r.withdrawn, c), paid, amt(bal, c), r.workNote)
        }
        val due = rows.sumOf { it.due }
        val wd = rows.sumOf { it.withdrawn }
        val totals = listOf(listOf("", "", "", "الإجمالي", fmtNum(rows.sumOf { it.overtimeHours }), amt(due, c), amt(wd, c), "", amt(bal, c), ""))
        val first = rows.firstOrNull()?.date ?: Dates.today()
        val last = rows.lastOrNull()?.date ?: Dates.today()
        return ReportDoc(
            ReportKind.STATEMENT.formNo, ReportKind.STATEMENT.title, p, period(first, last),
            listOf(Section(null, cols, data, totals, if (contract) listOf("المبلغ المتفق عليه" to amt(a.contractAmount, c), "المتبقي من المقاولة" to amt(bal, c)) else listOf("المتبقي للعامل" to amt(bal, c)))),
            listOf(
                "اسم العامل" to aw.worker.name, "رقم الكرت" to "${a.cardNo}", "المهنة" to aw.worker.profession,
                "الهاتف" to aw.worker.phone, "الاتفاق" to (if (contract) "مقاولة" else "يومية ${amt(a.dailyWage, c)}"), "عملة الحساب" to cname(c)
            ),
            true, appVersion = appVersion
        )
    }
}
