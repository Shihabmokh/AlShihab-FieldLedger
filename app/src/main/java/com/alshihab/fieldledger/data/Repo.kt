package com.alshihab.fieldledger.data

import android.content.Context
import androidx.room.withTransaction
import com.alshihab.fieldledger.security.TimeGuard
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money

class LockedException : Exception("الكشف مقفل ولا يمكن تعديله")
class DuplicateException(msg: String) : Exception(msg)
class StockException(msg: String) : Exception(msg)

/**
 * Single entry point for every write. Each write is wrapped in a transaction together with its
 * audit-log record, and every write to a daily statement re-checks the 24h lock.
 */
class Repo(private val db: LedgerDb, private val ctx: Context) {
    val dao: LedgerDao = db.dao()

    private fun now() = System.currentTimeMillis()

    private suspend fun audit(action: String, entity: String, id: Long, details: String) {
        dao.insertAudit(AuditLog(at = now(), action = action, entity = entity, entityId = id, details = details))
    }

    suspend fun logEvent(action: String, details: String) = audit(action, "النظام", 0, details)

    fun sheetEditable(s: Sheet?): Boolean = s == null || TimeGuard.canEdit(ctx, s.createdAt)

    // ---------- projects ----------
    suspend fun saveProject(p: Project): Long = db.withTransaction {
        if (p.id == 0L) {
            val id = dao.insertProject(p.copy(createdAt = now()))
            audit("إنشاء", "مشروع", id, p.name)
            id
        } else {
            val old = dao.project(p.id)
            dao.updateProject(p.copy(createdAt = old?.createdAt ?: p.createdAt))
            audit("تعديل", "مشروع", p.id, diff(old, p))
            p.id
        }
    }

    // ---------- currencies ----------
    suspend fun addCurrency(c: CurrencyEntity) = db.withTransaction {
        dao.insertCurrency(c)
        audit("إنشاء", "عملة", 0, "${c.code} - ${c.name}")
    }

    // ---------- workers ----------
    suspend fun saveWorker(w: Worker): Long = db.withTransaction {
        if (w.id == 0L) {
            val id = dao.insertWorker(w.copy(createdAt = now()))
            audit("إنشاء", "عامل", id, w.name)
            id
        } else {
            val old = dao.worker(w.id)
            dao.updateWorker(w.copy(createdAt = old?.createdAt ?: w.createdAt))
            audit("تعديل", "عامل", w.id, diff(old, w))
            w.id
        }
    }

    suspend fun saveAssignment(a: Assignment): Long = db.withTransaction {
        if (a.id == 0L) {
            if (dao.assignmentExists(a.projectId, a.workerId) > 0) {
                throw DuplicateException("هذا العامل مضاف للمشروع مسبقاً")
            }
            val card = (dao.maxCardNo(a.projectId) ?: 0) + 1
            val id = dao.insertAssignment(a.copy(createdAt = now(), cardNo = card))
            audit("إنشاء", "اتفاق عامل", id, "مشروع ${a.projectId} / عامل ${a.workerId} / كرت $card")
            id
        } else {
            val old = dao.assignment(a.id)
            dao.updateAssignment(a.copy(createdAt = old?.createdAt ?: a.createdAt, cardNo = old?.cardNo ?: a.cardNo))
            audit("تعديل", "اتفاق عامل", a.id, diff(old, a))
            a.id
        }
    }

    // ---------- sheets ----------
    private suspend fun getOrCreateSheet(projectId: Long, form: String, date: String): Sheet {
        dao.findSheet(projectId, form, date)?.let { return it }
        val serial = (dao.maxSerial(projectId, form) ?: 0) + 1
        val s = Sheet(projectId = projectId, form = form, date = date, serial = serial, createdAt = now())
        val id = dao.insertSheet(s)
        audit("إنشاء", "كشف $form", id, "رقم $serial بتاريخ $date")
        return s.copy(id = id)
    }

    // ---------- attendance / withdrawals (3F) ----------
    suspend fun saveAttendance(projectId: Long, date: String, rows: List<Attendance>) = db.withTransaction {
        attendanceInner(projectId, date, rows)
    }

    /** One transaction for the whole day: workers sheet (3F) and cash sheet (A2F/B2F). Null = section not saved. */
    suspend fun saveDay(
        projectId: Long,
        date: String,
        attendance: List<Attendance>?,
        cash: List<CashEntry>?,
        moves: Map<String, List<MaterialMove>> = emptyMap()
    ) = db.withTransaction {
        if (attendance != null) attendanceInner(projectId, date, attendance)
        if (cash != null) cashDayInner(projectId, date, cash)
        for ((type, list) in moves) movesInner(projectId, date, type, list)
        if (moves.isNotEmpty()) checkStock(projectId)
    }

    // ---------- materials ----------
    suspend fun saveMaterial(m: Material): Long = db.withTransaction {
        if (dao.materialNameCount(m.projectId, m.name, m.id) > 0) throw DuplicateException("الصنف «${m.name}» موجود مسبقاً")
        if (m.id == 0L) {
            val id = dao.insertMaterial(m.copy(createdAt = now()))
            audit("إنشاء", "صنف", id, "${m.name} (${m.unit})")
            id
        } else {
            val old = dao.material(m.id)
            dao.updateMaterial(m.copy(createdAt = old?.createdAt ?: m.createdAt))
            audit("تعديل", "صنف", m.id, diff(old, m))
            m.id
        }
    }

    // ---------- office wage list ----------
    suspend fun saveWageRate(w: WageRate): Long = db.withTransaction {
        if (w.id == 0L) {
            val id = dao.insertWageRate(w.copy(createdAt = now()))
            audit("إنشاء", "أجر المكتب", id, "${w.title}: ${Money.fmt(w.dailyWage)} ${w.currency}")
            id
        } else {
            val old = dao.wageRate(w.id)
            val fixed = w.copy(createdAt = old?.createdAt ?: w.createdAt, currency = old?.currency ?: w.currency)
            dao.updateWageRate(fixed)
            val n = dao.applyWageRate(w.id, fixed.dailyWage, fixed.overtimeRate)
            audit("تعديل", "أجر المكتب", w.id, "${diff(old, fixed)} - طُبّق على $n عامل")
            w.id
        }
    }

    // ---------- worker settlement (تصفية حساب) ----------
    class SettleItem(
        val assignmentId: Long,
        val accountAmount: Long,
        val payCurrency: String,
        val payAmount: Long,
        val factor: Double,
        val note: String,
        val deactivate: Boolean
    )

    /** Pays out each worker's remaining balance (in the agreement currency) and records it on today's settlement sheet. Returns attendance ids. */
    suspend fun settleWorkers(projectId: Long, items: List<SettleItem>): List<Long> = db.withTransaction {
        val date = Dates.today()
        val sheet = getOrCreateSheet(projectId, Forms.SETTLE, date)
        val existing = dao.attendanceForSheet(sheet.id).map { it.assignmentId }.toSet()
        items.map { it ->
            val a = dao.assignment(it.assignmentId) ?: throw IllegalStateException("worker")
            if (it.assignmentId in existing) throw DuplicateException("تمت تصفية حساب أحد العمال المحددين اليوم مسبقاً")
            val id = dao.insertAttendance(
                Attendance(
                    sheetId = sheet.id, projectId = projectId, assignmentId = it.assignmentId, date = date,
                    currency = a.currency, status = Status.SETTLE, due = 0, withdrawn = it.accountAmount,
                    workNote = it.note, updatedAt = now(), payCurrency = it.payCurrency, payAmount = it.payAmount, rate = it.factor
                )
            )
            if (it.deactivate && a.active) dao.updateAssignment(a.copy(active = false))
            audit(
                "تصفية", "حساب عامل", it.assignmentId,
                "صُرف ${Money.fmt(it.accountAmount)} ${a.currency} (دُفع ${Money.fmt(it.payAmount)} ${it.payCurrency})" +
                    if (it.deactivate) " وأُنهي عمله" else ""
            )
            id
        }
    }

    // ---------- corrections for locked statements ----------
    /** Worker correction: signed deltas in the account currency, recorded on today's correction sheet. */
    suspend fun addWorkerCorrection(projectId: Long, assignmentId: Long, dueDelta: Long, withdrawnDelta: Long, reason: String) = db.withTransaction {
        val a = dao.assignment(assignmentId) ?: return@withTransaction
        val date = Dates.today()
        val sheet = getOrCreateSheet(projectId, Forms.CORRECT, date)
        val old = dao.attendanceForSheet(sheet.id).firstOrNull { it.assignmentId == assignmentId }
        if (old == null) {
            dao.insertAttendance(
                Attendance(
                    sheetId = sheet.id, projectId = projectId, assignmentId = assignmentId, date = date, currency = a.currency,
                    status = Status.CORRECTION, due = dueDelta, withdrawn = withdrawnDelta, workNote = "تصحيح: $reason",
                    updatedAt = now(), payCurrency = a.currency, payAmount = withdrawnDelta, rate = 1.0
                )
            )
        } else {
            dao.updateAttendance(
                old.copy(
                    due = old.due + dueDelta, withdrawn = old.withdrawn + withdrawnDelta, payAmount = old.payAmount + withdrawnDelta,
                    workNote = old.workNote + " | تصحيح: " + reason, updatedAt = now()
                )
            )
        }
        audit("قيد تصحيحي", "حساب عامل", assignmentId, "المستحق ${Money.fmt(dueDelta)}، المسحوب ${Money.fmt(withdrawnDelta)} ${a.currency} - السبب: $reason")
    }

    // ---------- tamper-evident seals ----------
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Canonical text of a sheet and all its lines (explicit fields so future columns never change old hashes). */
    private suspend fun sheetContent(sh: Sheet): String {
        val sb = StringBuilder("S|${sh.id}|${sh.projectId}|${sh.form}|${sh.date}|${sh.serial}|${sh.createdAt}\n")
        dao.attendanceForSheet(sh.id).sortedBy { it.id }.forEach {
            sb.append("A|${it.id}|${it.assignmentId}|${it.status}|${it.overtimeHours}|${it.due}|${it.withdrawn}|${it.currency}|${it.payCurrency}|${it.payAmount}|${it.rate}|${it.workNote}\n")
        }
        dao.cashForSheet(sh.id).forEach {
            sb.append("C|${it.id}|${it.kind}|${it.category}|${it.currency}|${it.amount}|${it.docType}|${it.docNo}|${it.description}|${it.voided}|${it.correctionOf}|${it.reason}\n")
        }
        dao.movesForSheet(sh.id).forEach {
            sb.append("M|${it.id}|${it.materialId}|${it.type}|${it.qty}|${it.unitPrice}|${it.total}|${it.currency}|${it.shop}|${it.docNo}|${it.site}|${it.person}|${it.details}|${it.voided}\n")
        }
        return sb.toString()
    }

    /** Seals every sheet whose 24h window has closed, chaining each hash to the previous seal. */
    suspend fun sealLockedSheets(): Int = db.withTransaction {
        if (!TimeGuard.clockOk(ctx)) return@withTransaction 0
        val sealed = dao.seals().map { it.sheetId }.toSet()
        var last = dao.lastSeal()
        var n = 0
        dao.allSheets()
            .filter { it.id !in sealed && System.currentTimeMillis() - it.createdAt >= TimeGuard.EDIT_WINDOW_MS }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .forEach { sh ->
                val prev = last?.hash ?: "GENESIS"
                val seal = SheetSeal(sh.id, (last?.seq ?: 0) + 1, prev, sha(prev + "\n" + sheetContent(sh)), now())
                dao.insertSeal(seal)
                last = seal
                n++
            }
        n
    }

    class SealReport(val total: Int, val broken: List<Pair<Sheet?, String>>)

    /** Recomputes the whole chain; any edit to a sealed sheet (e.g. on a rooted phone) shows up here. */
    suspend fun verifySeals(): SealReport {
        val sheets = dao.allSheets().associateBy { it.id }
        val seals = dao.seals()
        val broken = mutableListOf<Pair<Sheet?, String>>()
        var prev = "GENESIS"
        for (seal in seals) {
            val sh = sheets[seal.sheetId]
            when {
                sh == null -> broken += null to "كشف مختوم مفقود (رقم ${seal.sheetId})"
                seal.prevHash != prev -> broken += sh to "انقطاع في سلسلة البصمات"
                sha(prev + "\n" + sheetContent(sh)) != seal.hash -> broken += sh to "محتوى الكشف تغيّر بعد قفله"
            }
            prev = seal.hash
        }
        if (broken.isNotEmpty()) logEvent("تنبيه أمني", "فشل التحقق من ${broken.size} كشف مختوم")
        return SealReport(seals.size, broken)
    }

    // ---------- raw database access for backup / restore ----------
    fun rawDb() = db.openHelper.writableDatabase

    // ---------- exchange rates ----------
    suspend fun addFx(f: FxRate): Long = db.withTransaction {
        val id = dao.insertFx(f.copy(createdAt = now()))
        audit("إنشاء", "سعر صرف", id, "1 ${f.fromCur} = ${f.rate} ${f.toCur} من ${f.date}")
        id
    }

    // ---------- persons ----------
    suspend fun addPerson(p: Person): Long = db.withTransaction {
        val id = dao.insertPerson(p.copy(createdAt = now()))
        audit("إنشاء", "شخص", id, "${p.name} ${p.phone}")
        id
    }

    // ---------- attachments ----------
    suspend fun addAttachment(a: Attachment): Long = db.withTransaction {
        val id = dao.insertAttachment(a.copy(createdAt = now()))
        audit("إرفاق", if (a.entity == AttachEntity.CASH) "قيد صندوق" else "حركة مواد", a.entityId, "${a.fileName} (${a.size / 1024} KB) sha256:${a.sha256.take(16)}")
        id
    }

    private suspend fun movesInner(projectId: Long, date: String, type: String, moves: List<MaterialMove>) {
        val form = MoveType.form(type)
        val existingSheet = dao.findSheet(projectId, form, date)
        if (!sheetEditable(existingSheet)) throw LockedException()
        val toWrite = moves.filter { it.id != 0L || (!it.voided && it.qty > 0) }
        if (toWrite.isEmpty()) return
        val sheet = existingSheet ?: getOrCreateSheet(projectId, form, date)
        for (m in toWrite) {
            val fixed = m.copy(type = type, total = if (type == MoveType.PURCHASE) Math.round(m.qty * m.unitPrice) else 0L)
            if (m.id == 0L) {
                val id = dao.insertMove(fixed.copy(sheetId = sheet.id, projectId = projectId, date = date, createdAt = now()))
                audit("إنشاء", "حركة مواد $form", id, describeMove(fixed))
            } else {
                val old = dao.move(m.id) ?: continue
                val updated = fixed.copy(sheetId = old.sheetId, projectId = old.projectId, date = old.date, createdAt = old.createdAt)
                if (updated != old) {
                    dao.updateMove(updated)
                    if (updated.voided && !old.voided) audit("إلغاء", "حركة مواد $form", m.id, describeMove(old))
                    else audit("تعديل", "حركة مواد $form", m.id, "${describeMove(old)} ← ${describeMove(updated)}")
                }
            }
        }
    }

    /** No material may go below zero: the whole day save is rolled back if it would. */
    private suspend fun checkStock(projectId: Long) {
        val negative = dao.stockNow(projectId).firstOrNull { it.qty < -0.0001 } ?: return
        val name = dao.material(negative.materialId)?.name ?: "صنف"
        throw StockException("الكمية المنصرفة من «$name» أكبر من الرصيد المتوفر في المخزن")
    }

    private fun describeMove(m: MaterialMove) =
        "${when (m.type) { MoveType.PURCHASE -> "شراء"; MoveType.OFFICE -> "توريد من المكتب"; MoveType.RETURN -> "إرجاع للمكتب"; else -> "صرف" }} " +
            "صنف ${m.materialId} كمية ${m.qty}" + if (m.type == MoveType.PURCHASE) " بقيمة ${Money.fmt(m.total)} ${m.currency}" else ""

    private suspend fun attendanceInner(projectId: Long, date: String, rows: List<Attendance>) {
        val existingSheet = dao.findSheet(projectId, Forms.WORKERS, date)
        if (!sheetEditable(existingSheet)) throw LockedException()
        val existing = existingSheet?.let { s -> dao.attendanceForSheet(s.id).associateBy { it.assignmentId } } ?: emptyMap()
        val toWrite = rows.filter { r ->
            existing.containsKey(r.assignmentId) ||
                !(r.withdrawn == 0L && r.due == 0L && r.workNote.isBlank() && (r.status == Status.ABSENT || r.status == Status.CONTRACT))
        }
        if (toWrite.isEmpty()) return
        val sheet = existingSheet ?: getOrCreateSheet(projectId, Forms.WORKERS, date)
        var changes = 0
        for (r in toWrite) {
            val row = r.copy(sheetId = sheet.id, projectId = projectId, date = date, updatedAt = now())
            val old = existing[r.assignmentId]
            if (old == null) {
                dao.insertAttendance(row.copy(id = 0))
                changes++
            } else {
                val updated = row.copy(id = old.id)
                if (updated.copy(updatedAt = old.updatedAt) != old) {
                    dao.updateAttendance(updated)
                    audit(
                        "تعديل", "كشف 3F", sheet.id,
                        "اتفاق ${old.assignmentId}: حالة ${old.status}→${updated.status}، " +
                            "مستحق ${Money.fmt(old.due)}→${Money.fmt(updated.due)}، " +
                            "مسحوب ${Money.fmt(old.withdrawn)}→${Money.fmt(updated.withdrawn)}"
                    )
                    changes++
                }
            }
        }
        if (changes > 0) audit("حفظ", "كشف 3F", sheet.id, "$date - $changes سطر")
    }

    private suspend fun cashDayInner(projectId: Long, date: String, entries: List<CashEntry>) {
        val existingSheet = dao.findSheet(projectId, Forms.CASH, date)
        if (!sheetEditable(existingSheet)) throw LockedException()
        val toWrite = entries.filter { it.id != 0L || (!it.voided && it.amount > 0) }
        if (toWrite.isEmpty()) return
        val sheet = existingSheet ?: getOrCreateSheet(projectId, Forms.CASH, date)
        for (e in toWrite) {
            if (e.id == 0L) {
                val id = dao.insertCash(e.copy(sheetId = sheet.id, projectId = projectId, date = date, createdAt = now()))
                audit("إنشاء", "قيد صندوق", id, describe(e))
            } else {
                val old = dao.cashEntry(e.id) ?: continue
                val updated = e.copy(sheetId = old.sheetId, projectId = old.projectId, date = old.date, createdAt = old.createdAt)
                if (updated != old) {
                    dao.updateCash(updated)
                    if (updated.voided && !old.voided) audit("إلغاء", "قيد صندوق", e.id, describe(old))
                    else audit("تعديل", "قيد صندوق", e.id, "${describe(old)} ← ${describe(updated)}")
                }
            }
        }
    }

    // ---------- cash box (A2F / B2F) ----------
    suspend fun saveCash(e: CashEntry): Long = db.withTransaction {
        val existingSheet = dao.findSheet(e.projectId, Forms.CASH, e.date)
        if (!sheetEditable(existingSheet)) throw LockedException()
        val sheet = existingSheet ?: getOrCreateSheet(e.projectId, Forms.CASH, e.date)
        if (e.id == 0L) {
            val id = dao.insertCash(e.copy(sheetId = sheet.id, createdAt = now()))
            audit("إنشاء", "قيد صندوق", id, describe(e))
            id
        } else {
            val old = dao.cashEntry(e.id)
            dao.updateCash(e.copy(sheetId = sheet.id, createdAt = old?.createdAt ?: now()))
            audit("تعديل", "قيد صندوق", e.id, "${old?.let { describe(it) }} ← ${describe(e)}")
            e.id
        }
    }

    suspend fun voidCash(e: CashEntry) = db.withTransaction {
        val sheet = dao.findSheet(e.projectId, Forms.CASH, e.date)
        if (!sheetEditable(sheet)) throw LockedException()
        dao.updateCash(e.copy(voided = true))
        audit("إلغاء", "قيد صندوق", e.id, describe(e))
    }

    private fun describe(e: CashEntry) =
        "${if (e.kind == CashKind.IN) "قبض" else "صرف"} ${Money.fmt(e.amount)} ${e.currency} - ${e.category} - ${e.description}"

    private fun diff(old: Any?, new: Any): String {
        if (old == null) return new.toString()
        val o = old.toString().substringAfter("(").substringBeforeLast(")").split(", ")
        val n = new.toString().substringAfter("(").substringBeforeLast(")").split(", ")
        return n.filterIndexed { i, s -> o.getOrNull(i) != s && !s.startsWith("createdAt") }
            .joinToString("، ")
            .ifBlank { "بدون تغيير" }
    }

    fun clockIncident(now: Long, last: Long) = "تم اكتشاف إرجاع ساعة الجهاز: الوقت الحالي ${Dates.dateTime(now)} أقدم من آخر وقت معروف ${Dates.dateTime(last)}"
}
