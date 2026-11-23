package com.alshihab.fieldledger.security

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Attachments (invoice photos, transfer notices...) are stored inside the app's private storage,
 * encrypted with AES-256-GCM using a key that never leaves the Android Keystore.
 * Opening one decrypts a temporary copy into the cache, which is wiped on every app start.
 */
object AttachmentStore {
    private const val ALIAS = "fieldledger_attach_key"
    const val MAX_BYTES = 15L * 1024 * 1024
    const val AUTHORITY_SUFFIX = ".files"

    class Stored(val storedName: String, val fileName: String, val mime: String, val size: Long, val sha256: String)

    private fun dir(c: Context) = File(c.filesDir, "att").apply { mkdirs() }
    private fun viewDir(c: Context) = File(c.cacheDir, "view").apply { mkdirs() }
    fun cameraDir(c: Context) = File(c.cacheDir, "cam").apply { mkdirs() }

    fun cleanCache(c: Context) {
        File(c.cacheDir, "view").deleteRecursively()
        File(c.cacheDir, "cam").deleteRecursively()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + AUTHORITY_SUFFIX, f)

    private fun displayName(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Reads, (re)compresses images, encrypts and stores. Throws IllegalArgumentException with an Arabic message. */
    fun import(c: Context, uri: Uri, fallbackName: String): Stored {
        val mimeIn = c.contentResolver.getType(uri) ?: if (fallbackName.endsWith(".pdf")) "application/pdf" else "image/jpeg"
        val raw = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("تعذر قراءة الملف")
        var name = displayName(c, uri) ?: fallbackName
        val (bytes, mime) = when {
            mimeIn == "application/pdf" -> raw to mimeIn
            mimeIn.startsWith("image/") -> {
                name = name.substringBeforeLast('.') + ".jpg"
                compressImage(raw) to "image/jpeg"
            }
            else -> throw IllegalArgumentException("نوع الملف غير مدعوم. المسموح: صورة أو PDF")
        }
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("حجم الملف أكبر من 15 ميجا")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(bytes)
        val stored = UUID.randomUUID().toString() + ".bin"
        File(dir(c), stored).outputStream().use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(enc)
        }
        return Stored(stored, name, mime, bytes.size.toLong(), sha)
    }

    private fun compressImage(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("تعذر قراءة الصورة")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Decrypts an attachment and verifies the SHA-256 recorded at import. */
    fun decrypt(c: Context, storedName: String, sha256: String): ByteArray {
        val data = File(dir(c), storedName).readBytes()
        val ivLen = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
        val sha = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
        if (sha != sha256) throw SecurityException("بصمة الملف لا تطابق المسجلة - قد يكون الملف عُدّل")
        return plain
    }

    /** Encrypts raw bytes under the device key and stores them with the given name (used by restore and signatures). */
    fun storeBytes(c: Context, storedName: String, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(bytes)
        File(dir(c), storedName).outputStream().use { out ->
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(enc)
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun newStoredName() = UUID.randomUUID().toString() + ".bin"

    /** Decrypts to the cache and returns a VIEW intent; verifies the SHA-256 recorded at import. */
    fun openIntent(c: Context, storedName: String, fileName: String, mime: String, sha256: String): Intent {
        val plain = decrypt(c, storedName, sha256)
        val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val f = File(viewDir(c), safe)
        f.writeBytes(plain)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(c, f), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
