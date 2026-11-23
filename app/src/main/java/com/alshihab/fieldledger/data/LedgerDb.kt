package com.alshihab.fieldledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alshihab.fieldledger.security.DbKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Project::class, CurrencyEntity::class, ExchangeRate::class, Worker::class,
        Assignment::class, Sheet::class, Attendance::class, CashEntry::class, AuditLog::class
    ],
    version = 1,
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
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO currencies(code, name, symbol, decimals) VALUES " +
                                "('YER', 'ريال يمني', 'ر.ي', 0), " +
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
