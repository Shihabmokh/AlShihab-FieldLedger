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

    @Query("SELECT currency, SUM(withdrawn) AS total FROM attendance WHERE projectId = :projectId GROUP BY currency")
    fun withdrawTotals(projectId: Long): Flow<List<CurrencyTotal>>

    @Query("SELECT currency, SUM(withdrawn) AS total FROM attendance WHERE projectId = :projectId AND date < :date GROUP BY currency")
    fun withdrawTotalsBefore(projectId: Long, date: String): Flow<List<CurrencyTotal>>

    @Query("SELECT currency, SUM(withdrawn) AS total FROM attendance WHERE projectId = :projectId AND date = :date GROUP BY currency")
    fun withdrawTotalsOn(projectId: Long, date: String): Flow<List<CurrencyTotal>>

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

    // ---------- audit ----------
    @Insert
    suspend fun insertAudit(a: AuditLog): Long

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT 500")
    fun audit(): Flow<List<AuditLog>>
}
