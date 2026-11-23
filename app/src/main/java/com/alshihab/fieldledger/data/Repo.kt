package com.alshihab.fieldledger.data

import android.content.Context
import androidx.room.withTransaction
import com.alshihab.fieldledger.security.TimeGuard
import com.alshihab.fieldledger.util.Dates
import com.alshihab.fieldledger.util.Money

class LockedException : Exception("الكشف مقفل ولا يمكن تعديله")
class DuplicateException(msg: String) : Exception(msg)

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
    suspend fun saveDay(projectId: Long, date: String, attendance: List<Attendance>?, cash: List<CashEntry>?) =
        db.withTransaction {
            if (attendance != null) attendanceInner(projectId, date, attendance)
            if (cash != null) cashDayInner(projectId, date, cash)
        }

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
