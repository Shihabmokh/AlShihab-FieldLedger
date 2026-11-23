package com.alshihab.fieldledger.security

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.alshihab.fieldledger.Graph
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup (.slb):
 *  - data.db : a SQLCipher copy of the whole database, keyed with the backup password (sqlcipher_export)
 *  - att/... : every attachment, decrypted from the device key and re-encrypted with AES-256-GCM
 *              under a PBKDF2 key derived from the same password
 *  - meta.json
 * Restore is only allowed into an empty ledger (a new phone or a fresh install), so nothing can be overwritten.
 */
object Backup {
    const val DB_VERSION = 5
    private const val ITER = 120_000

    private fun kdfAlg() = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun deriveKey(pw: String, salt: ByteArray, alg: String, iter: Int) =
        SecretKeySpec(SecretKeyFactory.getInstance(alg).generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded, "AES")

    private fun sqlQuote(s: String) = "'" + s.replace("'", "''") + "'"

    private fun tables(): List<String> {
        val out = mutableListOf<String>()
        Graph.repo.rawDb().query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')").use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    private fun columns(table: String, schema: String = "main"): List<String> {
        val out = mutableListOf<String>()
        Graph.repo.rawDb().query("PRAGMA $schema.table_info(`$table`)").use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
        }
        return out
    }

    fun create(ctx: Context, password: String): File {
        val work = File(ctx.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
        val dbCopy = File(work, "data.db")
        val db = Graph.repo.rawDb()
        db.execSQL("ATTACH DATABASE ${sqlQuote(dbCopy.absolutePath)} AS bk KEY ${sqlQuote(password)}")
        try {
            db.query("SELECT sqlcipher_export('bk')").use { it.moveToFirst() }
        } finally {
            db.execSQL("DETACH DATABASE bk")
        }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val alg = kdfAlg()
        val key = deriveKey(password, salt, alg, ITER)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "AlShihab-Backup-$stamp.slb")
        val atts = kotlinx.coroutines.runBlocking { Graph.repo.dao.allAttachments() }
        ZipOutputStream(out.outputStream()).use { zip ->
            val meta = JSONObject()
                .put("app", "AlShihab Field Ledger").put("dbVersion", DB_VERSION).put("created", System.currentTimeMillis())
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).put("kdf", alg).put("iter", ITER)
                .put("attachments", atts.size)
            zip.putNextEntry(ZipEntry("meta.json")); zip.write(meta.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.db")); dbCopy.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            for (a in atts) {
                val plain = try { AttachmentStore.decrypt(ctx, a.storedName, a.sha256) } catch (e: Exception) { continue }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key)
                val enc = c.doFinal(plain)
                zip.putNextEntry(ZipEntry("att/${a.storedName}"))
                zip.write(c.iv.size); zip.write(c.iv); zip.write(enc)
                zip.closeEntry()
            }
        }
        work.deleteRecursively()
        return out
    }

    /** Throws IllegalStateException with an Arabic message on any problem. */
    fun restore(ctx: Context, uri: Uri, password: String): Int {
        if (kotlinx.coroutines.runBlocking { Graph.repo.dao.projectCount() } > 0) {
            throw IllegalStateException("الاستعادة مسموحة فقط على دفتر فارغ (جهاز جديد أو تثبيت جديد) حتى لا تُستبدل بيانات موجودة")
        }
        val work = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val attDir = File(work, "att").apply { mkdirs() }
            var meta: JSONObject? = null
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name
                        if (name.contains("..")) continue
                        when {
                            name == "meta.json" -> meta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            name == "data.db" -> File(work, "data.db").outputStream().use { zip.copyTo(it) }
                            name.startsWith("att/") -> File(attDir, name.removePrefix("att/")).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            } ?: throw IllegalStateException("تعذر قراءة الملف")
            val m = meta ?: throw IllegalStateException("الملف ليس نسخة احتياطية من الدفتر الميداني")
            if (m.optInt("dbVersion") != DB_VERSION) throw IllegalStateException("النسخة الاحتياطية من إصدار مختلف من التطبيق. حدّث التطبيق أولاً")
            val dbFile = File(work, "data.db")
            if (!dbFile.exists()) throw IllegalStateException("الملف ناقص")

            val key = deriveKey(password, Base64.decode(m.getString("salt"), Base64.NO_WRAP), m.getString("kdf"), m.getInt("iter"))
            val db = Graph.repo.rawDb()
            db.execSQL("ATTACH DATABASE ${sqlQuote(dbFile.absolutePath)} AS bk KEY ${sqlQuote(password)}")
            var rows = 0
            try {
                try {
                    db.query("SELECT count(*) FROM bk.sqlite_master").use { it.moveToFirst() }
                } catch (e: Exception) {
                    throw IllegalStateException("كلمة المرور غير صحيحة")
                }
                db.beginTransaction()
                try {
                    for (t in tables()) {
                        val cols = columns(t, "main").intersect(columns(t, "bk").toSet())
                        if (cols.isEmpty()) continue
                        val list = cols.joinToString(", ") { "`$it`" }
                        val verb = if (t == "currencies") "INSERT OR IGNORE" else "INSERT"
                        if (t == "audit_log") {
                            val noId = cols.filter { it != "id" }.joinToString(", ") { "`$it`" }
                            db.execSQL("INSERT INTO main.`audit_log` ($noId) SELECT $noId FROM bk.`audit_log` ORDER BY id")
                        } else {
                            db.execSQL("$verb INTO main.`$t` ($list) SELECT $list FROM bk.`$t`")
                        }
                        db.query("SELECT changes()").use { if (it.moveToFirst()) rows += it.getInt(0) }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE bk")
            }
            // attachments back under this phone's key
            attDir.listFiles()?.forEach { f ->
                val data = f.readBytes()
                val ivLen = data[0].toInt()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(1, 1 + ivLen)))
                val plain = c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
                AttachmentStore.storeBytes(ctx, f.name, plain)
            }
            return rows
        } finally {
            work.deleteRecursively()
        }
    }
}
