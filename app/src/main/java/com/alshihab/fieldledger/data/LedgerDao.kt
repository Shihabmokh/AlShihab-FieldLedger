package com.alshihab.fieldledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {

    // ---------- projects ----------
    @Query("SELECT * FROM projects ORDER BY active DESC, id DESC")
    fun projects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun projectFlow(id: Long): Flow<Project?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: Long): Project?

    @Insert
    suspend fun insertProject(p: Project): Long

    @Update
    suspend fun updateProject(p: Project)

    // ---------- currencies ----------
    @Query("SELECT * FROM currencies ORDER BY code")
    fun currencies(): Flow<List<CurrencyEntity>>

    @Insert
    suspend fun insertCurrency(c: CurrencyEntity)

    // ---------- workers ----------
    @Query("SELECT * FROM workers ORDER BY active DESC, name")
    fun workers(): Flow<List<Worker>>

    @Query("SELECT * FROM workers WHERE id = :id")
    suspend fun worker(id: Long): Worker?

    @Insert
    suspend fun insertWorker(w: Worker): Long

    @Update
    suspend fun updateWorker(w: Worker)

    // ---------- assignments ----------
    @Transaction
    @Query("SELECT * FROM assignments WHERE projectId = :projectId ORDER BY active DESC, cardNo, id")
    fun assignments(projectId: Long): Flow<List<AssignmentWithWorker>>

    @Transaction
    @Query("SELECT * FROM assignments WHERE id = :id")
    fun assignmentFlow(id: Long): Flow<AssignmentWithWorker?>

    @Query("SELECT * FROM assignments WHERE id = :id")
    suspend fun assignment(id: Long): Assignment?

    @Query("SELECT COUNT(*) FROM assignments WHERE projectId = :projectId AND workerId = :workerId")
    suspend fun assignmentExists(projectId: Long, workerId: Long): Int

    @Query("SELECT MAX(cardNo) FROM assignments WHERE projectId = :projectId")
    suspend fun maxCardNo(projectId: Long): Int?

    @Insert
    suspend fun insertAssignment(a: Assignment): Long

    @Update
    suspend fun updateAssignment(a: Assignment)

    // ---------- sheets ----------
    @Query("SELECT * FROM sheets WHERE projectId = :projectId AND form = :form AND date = :date")
    suspend fun findSheet(projectId: Long, form: String, date: String): Sheet?

    @Query("SELECT * FROM sheets WHERE projectId = :projectId AND form = :form AND date = :date")
    fun sheetFlow(projectId: Long, form: String, date: String): Flow<Sheet?>

    @Query("SELECT MAX(serial) FROM sheets WHERE projectId = :projectId AND form = :form")
    suspend fun maxSerial(projectId: Long, form: String): Int?

    @Insert
    suspend fun insertSheet(s: Sheet): Long

    // ---------- attendance (3F) ----------
    @Query("SELECT * FROM attendance WHERE sheetId = :sheetId")
    suspend fun attendanceForSheet(sheetId: Long): List<Attendance>

    @Query("SELECT * FROM attendance WHERE projectId = :projectId AND date = :date")
    fun attendanceOn(projectId: Long, date: String): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance WHERE assignmentId = :assignmentId ORDER BY date")
    fun attendanceForAssignment(assignmentId: Long): Flow<List<Attendance>>

    @Insert
    suspend fun insertAttendance(a: Attendance): Long

    @Update
    suspend fun updateAttendance(a: Attendance)

    @Query(
        "SELECT assignmentId, SUM(due) AS due, SUM(withdrawn) AS withdrawn, " +
            "SUM(CASE WHEN status = 'P' THEN 1.0 WHEN status = 'H' THEN 0.5 ELSE 0.0 END) AS days, " +
            "SUM(overtimeHours) AS ot FROM attendance WHERE projectId = :projectId GROUP BY assignmentId"
    )
    fun assignmentTotals(projectId: Long): Flow<List<AssignmentTotal>>

    /** cash paid to workers, in the currency actually paid (cash-box side) */
    @Query("SELECT payCurrency AS currency, SUM(payAmount) AS total FROM attendance WHERE projectId = :projectId AND payAmount != 0 GROUP BY payCurrency")
    fun withdrawTotals(projectId: Long): Flow<List<CurrencyTotal>>

    @Query("SELECT payCurrency AS currency, SUM(payAmount) AS total FROM attendance WHERE projectId = :projectId AND payAmount != 0 AND date < :date GROUP BY payCurrency")
    fun withdrawTotalsBefore(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    @Query("SELECT payCurrency AS currency, SUM(payAmount) AS total FROM attendance WHERE projectId = :projectId AND payAmount != 0 AND date = :date GROUP BY payCurrency")
    fun withdrawTotalsOn(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    /** withdrawals converted to each worker's account currency (worker-account side) */
    @Query("SELECT currency, SUM(withdrawn) AS total FROM attendance WHERE projectId = :projectId GROUP BY currency")
    fun accountWithdrawTotals(projectId: Long): Flow<List<CurrencyTotal>>

    @Query("SELECT currency, SUM(due) AS total FROM attendance WHERE projectId = :projectId GROUP BY currency")
    fun dueTotals(projectId: Long): Flow<List<CurrencyTotal>>

    // ---------- cash (A2F / B2F) ----------
    @Query("SELECT * FROM cash_entries WHERE projectId = :projectId AND date = :date ORDER BY id")
    fun cashOn(projectId: Long, date: String): Flow<List<CashEntry>>

    @Query("SELECT * FROM cash_entries WHERE projectId = :projectId AND date = :date ORDER BY id")
    suspend fun cashOnList(projectId: Long, date: String): List<CashEntry>

    @Query("SELECT * FROM cash_entries WHERE id = :id")
    suspend fun cashEntry(id: Long): CashEntry?

    @Insert
    suspend fun insertCash(e: CashEntry): Long

    @Update
    suspend fun updateCash(e: CashEntry)

    @Query(
        "SELECT currency, SUM(CASE WHEN kind = 'IN' THEN amount ELSE -amount END) AS total " +
            "FROM cash_entries WHERE projectId = :projectId AND voided = 0 GROUP BY currency"
    )
    fun cashNet(projectId: Long): Flow<List<CurrencyTotal>>

    @Query(
        "SELECT currency, SUM(CASE WHEN kind = 'IN' THEN amount ELSE -amount END) AS total " +
            "FROM cash_entries WHERE projectId = :projectId AND voided = 0 AND date < :date GROUP BY currency"
    )
    fun cashNetBefore(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    @Query(
        "SELECT category, currency, SUM(amount) AS total FROM cash_entries " +
            "WHERE projectId = :projectId AND voided = 0 AND kind = :kind GROUP BY category, currency ORDER BY category"
    )
    fun categoryTotals(projectId: Long, kind: String): Flow<List<CategoryTotal>>

    // ---------- materials ----------
    @Query("SELECT * FROM materials WHERE projectId = :projectId ORDER BY active DESC, name")
    fun materials(projectId: Long): Flow<List<Material>>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun material(id: Long): Material?

    @Query("SELECT COUNT(*) FROM materials WHERE projectId = :projectId AND name = :name AND id != :exceptId")
    suspend fun materialNameCount(projectId: Long, name: String, exceptId: Long): Int

    @Insert
    suspend fun insertMaterial(m: Material): Long

    @Update
    suspend fun updateMaterial(m: Material)

    @Query(
        "SELECT materialId, SUM(CASE WHEN type IN ('I', 'R') THEN -qty ELSE qty END) AS qty " +
            "FROM material_moves WHERE projectId = :projectId AND voided = 0 GROUP BY materialId"
    )
    fun stock(projectId: Long): Flow<List<StockQty>>

    @Query(
        "SELECT materialId, SUM(CASE WHEN type IN ('I', 'R') THEN -qty ELSE qty END) AS qty " +
            "FROM material_moves WHERE projectId = :projectId AND voided = 0 GROUP BY materialId"
    )
    suspend fun stockNow(projectId: Long): List<StockQty>

    @Query("SELECT * FROM material_moves WHERE projectId = :projectId AND date = :date ORDER BY id")
    suspend fun movesOnList(projectId: Long, date: String): List<MaterialMove>

    @Query("SELECT * FROM material_moves WHERE materialId = :materialId ORDER BY date, id")
    fun movesForMaterial(materialId: Long): Flow<List<MaterialMove>>

    @Query("SELECT * FROM material_moves WHERE id = :id")
    suspend fun move(id: Long): MaterialMove?

    @Insert
    suspend fun insertMove(m: MaterialMove): Long

    @Update
    suspend fun updateMove(m: MaterialMove)

    @Query(
        "SELECT currency, SUM(total) AS total FROM material_moves " +
            "WHERE projectId = :projectId AND type = 'P' AND voided = 0 GROUP BY currency"
    )
    fun purchaseTotals(projectId: Long): Flow<List<CurrencyTotal>>

    @Query(
        "SELECT currency, SUM(total) AS total FROM material_moves " +
            "WHERE projectId = :projectId AND type = 'P' AND voided = 0 AND date < :date GROUP BY currency"
    )
    fun purchaseTotalsBefore(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    @Query(
        "SELECT currency, SUM(total) AS total FROM material_moves " +
            "WHERE projectId = :projectId AND type = 'P' AND voided = 0 AND date = :date GROUP BY currency"
    )
    fun purchaseTotalsOn(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    @Query(
        "SELECT materialId, " +
            "SUM(CASE WHEN date < :from THEN (CASE WHEN type IN ('I', 'R') THEN -qty ELSE qty END) ELSE 0 END) AS opening, " +
            "SUM(CASE WHEN date >= :from AND date <= :to AND type = 'P' THEN qty ELSE 0 END) AS purchased, " +
            "SUM(CASE WHEN date >= :from AND date <= :to AND type = 'O' THEN qty ELSE 0 END) AS office, " +
            "SUM(CASE WHEN date >= :from AND date <= :to AND type = 'I' THEN qty ELSE 0 END) AS issued, " +
            "SUM(CASE WHEN date >= :from AND date <= :to AND type = 'R' THEN qty ELSE 0 END) AS returned, " +
            "SUM(CASE WHEN date >= :from AND date <= :to AND type = 'P' THEN total ELSE 0 END) AS purchasedValue " +
            "FROM material_moves WHERE projectId = :projectId AND voided = 0 AND date <= :to GROUP BY materialId"
    )
    fun stockPeriod(projectId: Long, from: String, to: String): Flow<List<MaterialPeriod>>

    // ---------- office wage list ----------
    @Query("SELECT * FROM wage_rates ORDER BY active DESC, title")
    fun wageRates(): Flow<List<WageRate>>

    @Query("SELECT * FROM wage_rates WHERE id = :id")
    suspend fun wageRate(id: Long): WageRate?

    @Insert
    suspend fun insertWageRate(w: WageRate): Long

    @Update
    suspend fun updateWageRate(w: WageRate)

    @Query("UPDATE assignments SET dailyWage = :wage, overtimeRate = :ot WHERE wageRateId = :rateId")
    suspend fun applyWageRate(rateId: Long, wage: Long, ot: Long): Int

    // ---------- persons ----------
    @Query("SELECT * FROM persons WHERE projectId = :projectId ORDER BY name")
    fun persons(projectId: Long): Flow<List<Person>>

    @Insert
    suspend fun insertPerson(p: Person): Long

    // ---------- attachments ----------
    @Query("SELECT * FROM attachments WHERE entity = :entity AND entityId = :entityId ORDER BY id")
    fun attachments(entity: String, entityId: Long): Flow<List<Attachment>>

    @Query("SELECT entityId, COUNT(*) AS n FROM attachments WHERE projectId = :projectId AND entity = :entity GROUP BY entityId")
    fun attachmentCounts(projectId: Long, entity: String): Flow<List<AttachmentCount>>

    @Insert
    suspend fun insertAttachment(a: Attachment): Long

    // ---------- reporting (one-shot reads) ----------
    @Query("SELECT * FROM attendance WHERE projectId = :projectId AND date >= :from AND date <= :to ORDER BY date")
    suspend fun attendanceBetween(projectId: Long, from: String, to: String): List<Attendance>

    @Query("SELECT * FROM cash_entries WHERE projectId = :projectId AND date >= :from AND date <= :to ORDER BY date, id")
    suspend fun cashBetween(projectId: Long, from: String, to: String): List<CashEntry>

    @Query("SELECT * FROM material_moves WHERE projectId = :projectId AND date >= :from AND date <= :to ORDER BY date, id")
    suspend fun movesBetween(projectId: Long, from: String, to: String): List<MaterialMove>

    @Query("SELECT * FROM material_moves WHERE projectId = :projectId AND date < :date AND voided = 0")
    suspend fun movesBefore(projectId: Long, date: String): List<MaterialMove>

    @Query("SELECT * FROM cash_entries WHERE projectId = :projectId AND date < :date AND voided = 0")
    suspend fun cashBefore(projectId: Long, date: String): List<CashEntry>

    @Query("SELECT * FROM attendance WHERE projectId = :projectId AND date < :date")
    suspend fun attendanceBefore(projectId: Long, date: String): List<Attendance>

    @Transaction
    @Query("SELECT * FROM assignments WHERE projectId = :projectId ORDER BY cardNo, id")
    suspend fun assignmentsList(projectId: Long): List<AssignmentWithWorker>

    @Query("SELECT * FROM materials WHERE projectId = :projectId ORDER BY name")
    suspend fun materialsList(projectId: Long): List<Material>

    @Query("SELECT * FROM currencies ORDER BY code")
    suspend fun currenciesList(): List<CurrencyEntity>

    @Query("SELECT * FROM attendance WHERE assignmentId = :assignmentId ORDER BY date")
    suspend fun attendanceForAssignmentList(assignmentId: Long): List<Attendance>

    // ---------- exchange rates ----------
    @Query("SELECT * FROM fx_rates ORDER BY date DESC, id DESC")
    fun fxRates(): Flow<List<FxRate>>

    @Query("SELECT * FROM fx_rates ORDER BY date DESC, id DESC")
    suspend fun fxRatesList(): List<FxRate>

    @Insert
    suspend fun insertFx(f: FxRate): Long

    // ---------- seals ----------
    @Query("SELECT * FROM sheets ORDER BY id")
    suspend fun allSheets(): List<Sheet>

    @Query("SELECT * FROM sheet_seals ORDER BY seq")
    suspend fun seals(): List<SheetSeal>

    @Query("SELECT * FROM sheet_seals ORDER BY seq DESC LIMIT 1")
    suspend fun lastSeal(): SheetSeal?

    @Insert
    suspend fun insertSeal(s: SheetSeal)

    @Query("SELECT COUNT(*) FROM sheet_seals")
    fun sealCount(): Flow<Int>

    @Query("SELECT * FROM cash_entries WHERE sheetId = :sheetId ORDER BY id")
    suspend fun cashForSheet(sheetId: Long): List<CashEntry>

    @Query("SELECT * FROM material_moves WHERE sheetId = :sheetId ORDER BY id")
    suspend fun movesForSheet(sheetId: Long): List<MaterialMove>

    @Query("SELECT * FROM attachments WHERE projectId = :projectId ORDER BY id")
    suspend fun attachmentsForProject(projectId: Long): List<Attachment>

    @Query("SELECT * FROM attachments ORDER BY id")
    suspend fun allAttachments(): List<Attachment>

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun attendanceById(id: Long): Attendance?

    @Query("SELECT * FROM attachments WHERE entity = :entity AND entityId = :entityId ORDER BY id")
    suspend fun attachmentsList(entity: String, entityId: Long): List<Attachment>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun projectCount(): Int

    // ---------- audit ----------
    @Insert
    suspend fun insertAudit(a: AuditLog): Long

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT 500")
    fun audit(): Flow<List<AuditLog>>
}
