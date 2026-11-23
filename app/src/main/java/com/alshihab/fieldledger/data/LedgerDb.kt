package com.alshihab.fieldledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alshihab.fieldledger.security.DbKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Project::class, CurrencyEntity::class, ExchangeRate::class, Worker::class,
        Assignment::class, Sheet::class, Attendance::class, CashEntry::class, AuditLog::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        /** Tables whose rows may never be deleted (enforced inside the encrypted DB itself). */
        private val NO_DELETE = listOf(
            "projects", "workers", "assignments", "sheets",
            "attendance", "cash_entries", "audit_log", "currencies"
        )

        fun build(ctx: Context): LedgerDb {
            val passphrase = DbKeyManager.getPassphrase(ctx)
            return Room.databaseBuilder(ctx, LedgerDb::class.java, "ledger.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO currencies(code, name, symbol, decimals) VALUES " +
                                "('YER', 'ريال يمني (قديم)', 'ر.ي', 0), " +
                                "('YRN', 'ريال يمني (جديد)', 'ر.ي.ج', 0), " +
                                "('SAR', 'ريال سعودي', 'ر.س', 2), " +
                                "('USD', 'دولار أمريكي', '$', 2)"
                        )
                        installGuards(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        installGuards(db)
                    }
                })
                .build()
        }

        /** v2: per-project card numbers, new Yemeni rial */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assignments ADD COLUMN cardNo INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE assignments SET cardNo = (SELECT COUNT(*) FROM assignments a2 " +
                        "WHERE a2.projectId = assignments.projectId AND a2.id <= assignments.id)"
                )
                db.execSQL("UPDATE currencies SET name = 'ريال يمني (قديم)' WHERE code = 'YER'")
                db.execSQL(
                    "INSERT OR IGNORE INTO currencies(code, name, symbol, decimals) " +
                        "VALUES ('YRN', 'ريال يمني (جديد)', 'ر.ي.ج', 0)"
                )
            }
        }

        private fun installGuards(db: SupportSQLiteDatabase) {
            for (t in NO_DELETE) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS guard_no_delete_$t BEFORE DELETE ON $t " +
                        "BEGIN SELECT RAISE(ABORT, 'delete not allowed'); END"
                )
            }
            // audit log is append-only
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS guard_no_update_audit BEFORE UPDATE ON audit_log " +
                    "BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END"
            )
        }
    }
}
