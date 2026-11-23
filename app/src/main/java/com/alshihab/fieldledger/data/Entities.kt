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
    @ColumnInfo(defaultValue = "0") val cardNo: Int = 0,
    /** 0 = wage typed manually; otherwise the office wage-rate it was taken from */
    @ColumnInfo(defaultValue = "0") val wageRateId: Long = 0
)

data class AssignmentWithWorker(
    @Embedded val a: Assignment,
    @Relation(parentColumn = "workerId", entityColumn = "id") val worker: Worker
)

object Forms {
    const val WORKERS = "3F"
    const val CASH = "A2F"
    const val PURCHASE = "4F"
    const val OFFICE = "OFS"
    const val ISSUE = "31F"
    const val RETURN = "RET"
    const val SETTLE = "SET"
    const val CORRECT = "COR"
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
    const val SETTLE = "S"      // تصفية حساب
    const val CORRECTION = "X"  // قيد تصحيحي
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
    /** amount withdrawn, converted to the worker's account currency [currency] */
    val withdrawn: Long = 0,
    val workNote: String = "",
    val updatedAt: Long = 0,
    /** currency actually paid out of the cash box (may differ from the account currency) */
    @ColumnInfo(defaultValue = "''") val payCurrency: String = "",
    /** amount actually paid, in [payCurrency] */
    @ColumnInfo(defaultValue = "0") val payAmount: Long = 0,
    /** 1 unit of payCurrency = rate units of the account currency */
    @ColumnInfo(defaultValue = "1") val rate: Double = 1.0
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
    val createdAt: Long = 0,
    /** id of the locked entry this one corrects (0 = normal entry) */
    @ColumnInfo(defaultValue = "0") val correctionOf: Long = 0,
    @ColumnInfo(defaultValue = "''") val reason: String = ""
)

/** Exchange rate effective from [date]: 1 [fromCur] = [rate] [toCur]. Never deleted; a newer date supersedes it. */
@Entity(tableName = "fx_rates")
data class FxRate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val fromCur: String,
    val toCur: String,
    val rate: Double,
    val createdAt: Long = 0
)

/** Tamper-evidence: each locked sheet is sealed with SHA-256(content + previous seal). */
@Entity(tableName = "sheet_seals")
data class SheetSeal(
    @PrimaryKey val sheetId: Long,
    val seq: Long,
    val prevHash: String,
    val hash: String,
    val sealedAt: Long
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

/** Material catalogue of a project (الأصناف). */
@Entity(
    tableName = "materials",
    indices = [Index("projectId"), Index(value = ["projectId", "name"], unique = true)]
)
data class Material(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val unit: String,
    val minQty: Double = 0.0,
    val active: Boolean = true,
    val createdAt: Long = 0,
    /** M = مواد (consumed), E = معدات (supplied to site and returned) */
    @ColumnInfo(defaultValue = "'M'") val kind: String = ItemKind.MATERIAL
)

object ItemKind {
    const val MATERIAL = "M"
    const val EQUIPMENT = "E"
}

object MoveType {
    const val PURCHASE = "P"  // 4F  مشتراة - تدخل المخزن وتُخصم من الصندوق
    const val OFFICE = "O"    // موردة من المكتب - تدخل المخزن بالكمية فقط
    const val ISSUE = "I"     // 31F منصرفة من المخزن
    const val RETURN = "R"    // معدات مرتجعة للمكتب

    fun form(type: String) = when (type) {
        PURCHASE -> Forms.PURCHASE
        OFFICE -> Forms.OFFICE
        RETURN -> Forms.RETURN
        else -> Forms.ISSUE
    }

    fun isOut(type: String) = type == ISSUE || type == RETURN
}

@Entity(
    tableName = "material_moves",
    indices = [Index("sheetId"), Index("projectId"), Index("materialId")]
)
data class MaterialMove(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val projectId: Long,
    val materialId: Long,
    val date: String,
    val type: String,
    val qty: Double,
    val unitPrice: Long = 0,
    val total: Long = 0,
    val currency: String = "",
    /** shop name for purchases */
    val shop: String = "",
    /** invoice no. / delivery note no. */
    val docNo: String = "",
    /** work site for issues */
    val site: String = "",
    /** receiver (office supply) or the person doing the work (issue) */
    val person: String = "",
    val details: String = "",
    val voided: Boolean = false,
    val createdAt: Long = 0
)

// ---- query result types ----
data class StockQty(val materialId: Long, val qty: Double)

data class MaterialPeriod(
    val materialId: Long,
    val opening: Double,
    val purchased: Double,
    val office: Double,
    val issued: Double,
    val returned: Double,
    val purchasedValue: Long
)

/** Daily wage list set by the office (protected by the office password). */
@Entity(tableName = "wage_rates")
data class WageRate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val currency: String,
    val dailyWage: Long,
    val overtimeRate: Long = 0,
    val active: Boolean = true,
    val createdAt: Long = 0
)

/** People who are not project workers (receivers, drivers, technicians...). */
@Entity(tableName = "persons", indices = [Index("projectId")])
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val phone: String = "",
    val createdAt: Long = 0
)

object AttachEntity {
    const val CASH = "cash"
    const val MOVE = "move"
}

/** Encrypted file (invoice photo, transfer notice PDF...) attached to an entry. */
@Entity(tableName = "attachments", indices = [Index("projectId"), Index(value = ["entity", "entityId"])])
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val entity: String,
    val entityId: Long,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val storedName: String,
    val createdAt: Long = 0
)

data class CurrencyTotal(val currency: String, val total: Long)

data class AssignmentTotal(
    val assignmentId: Long,
    val due: Long,
    val withdrawn: Long,
    val days: Double,
    val ot: Double
)

data class AttachmentCount(val entityId: Long, val n: Int)

data class CategoryTotal(val category: String, val currency: String, val total: Long)
