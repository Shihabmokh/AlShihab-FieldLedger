package com.alshihab.fieldledger.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** All money values are stored as Long in minor units (amount x 100). Dates are ISO yyyy-MM-dd. */

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val village: String = "",
    val uzla: String = "",
    val district: String = "",
    val governorate: String = "",
    val site: String = "",
    val client: String = "",
    val contractNo: String = "",
    val startDate: String = "",
    val baseCurrency: String = "YER",
    val accountantName: String = "",
    val supervisorName: String = "",
    val engineerName: String = "",
    val active: Boolean = true,
    val createdAt: Long = 0
)

@Entity(tableName = "currencies")
data class CurrencyEntity(
    @PrimaryKey val code: String,
    val name: String,
    val symbol: String,
    val decimals: Int
)

@Entity(tableName = "exchange_rates", indices = [Index("projectId")])
data class ExchangeRate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val date: String,
    val currency: String,
    /** how many units of the project's base currency equal 1 unit of [currency] */
    val rateToBase: Double,
    val createdAt: Long
)

@Entity(tableName = "workers")
data class Worker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val profession: String = "",
    val phone: String = "",
    val cardNo: String = "",
    val notes: String = "",
    val active: Boolean = true,
    val createdAt: Long = 0
)

object PayType {
    const val DAILY = "DAILY"
    const val CONTRACT = "CONTRACT"
}

object WorkerCategory {
    const val BASIC = "BASIC"   // أساسي - كشف 24T
    const val TEMP = "TEMP"     // مؤقت - كشف 25T
}

@Entity(
    tableName = "assignments",
    indices = [Index("projectId"), Index("workerId"), Index(value = ["projectId", "workerId"], unique = true)]
)
data class Assignment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val workerId: Long,
    val category: String = WorkerCategory.TEMP,
    val payType: String = PayType.DAILY,
    val currency: String = "YER",
    val dailyWage: Long = 0,
    val overtimeRate: Long = 0,
    val baseHours: Int = 8,
    val contractDesc: String = "",
    val contractAmount: Long = 0,
    val progress: Int = 0,
    val active: Boolean = true,
    val createdAt: Long = 0,
    /** card number, auto-assigned sequentially inside each project */
    @ColumnInfo(defaultValue = "0") val cardNo: Int = 0
)

data class AssignmentWithWorker(
    @Embedded val a: Assignment,
    @Relation(parentColumn = "workerId", entityColumn = "id") val worker: Worker
)

object Forms {
    const val WORKERS = "3F"
    const val CASH = "A2F"
}

/** A daily statement (كشف). Editable for 24h after [createdAt], then locked forever. */
@Entity(tableName = "sheets", indices = [Index(value = ["projectId", "form", "date"], unique = true)])
data class Sheet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val form: String,
    val date: String,
    val serial: Int,
    val createdAt: Long,
    val note: String = ""
)

object Status {
    const val PRESENT = "P"
    const val HALF = "H"
    const val ABSENT = "A"
    const val CONTRACT = "C"
}

@Entity(
    tableName = "attendance",
    indices = [Index("sheetId"), Index("assignmentId"), Index("projectId"), Index(value = ["sheetId", "assignmentId"], unique = true)]
)
data class Attendance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val projectId: Long,
    val assignmentId: Long,
    val date: String,
    val currency: String,
    val status: String,
    val overtimeHours: Double = 0.0,
    val due: Long = 0,
    val withdrawn: Long = 0,
    val workNote: String = "",
    val updatedAt: Long = 0
)

object CashKind {
    const val IN = "IN"
    const val OUT = "OUT"
}

@Entity(tableName = "cash_entries", indices = [Index("sheetId"), Index("projectId")])
data class CashEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val projectId: Long,
    val date: String,
    val kind: String,
    val category: String,
    val currency: String,
    val amount: Long,
    val docType: String = "",
    val docNo: String = "",
    val description: String = "",
    val voided: Boolean = false,
    val createdAt: Long = 0
)

@Entity(tableName = "audit_log")
data class AuditLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val action: String,
    val entity: String,
    val entityId: Long,
    val details: String
)

// ---- query result types ----
data class CurrencyTotal(val currency: String, val total: Long)

data class AssignmentTotal(
    val assignmentId: Long,
    val due: Long,
    val withdrawn: Long,
    val days: Double,
    val ot: Double
)

data class CategoryTotal(val category: String, val currency: String, val total: Long)
