package com.alshihab.fieldledger.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

private fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/**
 * The SQLCipher passphrase is 32 random bytes, wrapped with an AES-256-GCM key that lives
 * inside the Android Keystore (hardware-backed where available) and never leaves it.
 */
object DbKeyManager {
    private const val ALIAS = "fieldledger_db_wrap_key"
    private const val PREFS = "secure_meta"

    @Synchronized
    fun getPassphrase(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = getOrCreateKey()
        val enc = prefs.getString("db_key_enc", null)
        val iv = prefs.getString("db_key_iv", null)
        if (enc != null && iv != null) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(iv)))
            return c.doFinal(b64d(enc))
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = hex(raw).toByteArray(Charsets.US_ASCII)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val out = c.doFinal(passphrase)
        prefs.edit()
            .putString("db_key_enc", b64e(out))
            .putString("db_key_iv", b64e(c.iv))
            .commit()
        return passphrase
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}

/** App PIN (and the separate office password): salted PBKDF2 hash, constant-time compare, escalating lockout. */
val PinManager = PinStore("pin_meta")

/** Office password: protects the wage list and office-only settings from the field accountant. */
val OfficePin = PinStore("office_meta")

class PinStore(private val PREFS: String) {
    private val ITER = 60_000
    val MIN_LEN = 4
    val MAX_LEN = 8

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(c: Context) = prefs(c).contains("hash")

    private fun algo() = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun derive(pin: String, salt: ByteArray, alg: String, iter: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iter, 256)
        return SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
    }

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val alg = algo()
        prefs(c).edit()
            .putString("hash", b64e(derive(pin, salt, alg, ITER)))
            .putString("salt", b64e(salt))
            .putString("alg", alg)
            .putInt("iter", ITER)
            .putInt("fails", 0)
            .putLong("lockUntil", 0)
            .commit()
    }

    /** milliseconds until another attempt is allowed (0 = allowed now) */
    fun lockoutRemaining(c: Context): Long {
        val until = prefs(c).getLong("lockUntil", 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun verify(c: Context, pin: String): Boolean {
        val p = prefs(c)
        if (lockoutRemaining(c) > 0) return false
        val hash = p.getString("hash", null) ?: return false
        val salt = b64d(p.getString("salt", "")!!)
        val alg = p.getString("alg", algo())!!
        val iter = p.getInt("iter", ITER)
        val ok = MessageDigest.isEqual(derive(pin, salt, alg, iter), b64d(hash))
        if (ok) {
            p.edit().putInt("fails", 0).putLong("lockUntil", 0).apply()
        } else {
            val fails = p.getInt("fails", 0) + 1
            val e = p.edit().putInt("fails", fails)
            if (fails >= 5) {
                val wait = (30_000L shl (fails - 5).coerceAtMost(5)).coerceAtMost(15 * 60_000L)
                e.putLong("lockUntil", System.currentTimeMillis() + wait)
            }
            e.apply()
        }
        return ok
    }

    fun biometricEnabled(c: Context) = prefs(c).getBoolean("bio", false)
    fun setBiometric(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bio", on).apply()
}

/** Rules the office sets for field accountants. */
object OfficePolicy {
    private fun prefs(c: Context) = c.getSharedPreferences("office_policy", Context.MODE_PRIVATE)
    fun allowManualWage(c: Context) = prefs(c).getBoolean("manual_wage", true)
    fun setAllowManualWage(c: Context, on: Boolean) = prefs(c).edit().putBoolean("manual_wage", on).apply()

    /** last exchange rate used for a currency pair, to pre-fill the next entry */
    fun lastRate(c: Context, from: String, to: String): Double? =
        prefs(c).getString("rate_${from}_$to", null)?.toDoubleOrNull()

    fun saveRate(c: Context, from: String, to: String, rate: Double) =
        prefs(c).edit().putString("rate_${from}_$to", rate.toString()).apply()
}

/**
 * Guards the 24-hour edit window against the phone clock being moved backwards.
 * The highest wall-clock time ever seen is remembered; if "now" is earlier than that
 * (beyond a small tolerance) the clock was rolled back and all editing is refused
 * until real time catches up again.
 */
object TimeGuard {
    const val EDIT_WINDOW_MS = 24L * 60 * 60 * 1000
    private const val TOLERANCE_MS = 10L * 60 * 1000
    private const val PREFS = "time_guard"

    /** called once per detected rollback (now, lastKnown) */
    var onRollback: ((Long, Long) -> Unit)? = null

    @Synchronized
    fun clockOk(c: Context): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = p.getLong("last", 0L)
        return if (last > 0 && now < last - TOLERANCE_MS) {
            if (!p.getBoolean("flagged", false)) {
                p.edit().putBoolean("flagged", true).apply()
                onRollback?.invoke(now, last)
            }
            false
        } else {
            p.edit().putLong("last", maxOf(now, last)).putBoolean("flagged", false).apply()
            true
        }
    }

    fun canEdit(c: Context, createdAt: Long): Boolean {
        if (!clockOk(c)) return false
        val age = System.currentTimeMillis() - createdAt
        return age in 0 until EDIT_WINDOW_MS
    }

    fun remainingMs(createdAt: Long): Long =
        (createdAt + EDIT_WINDOW_MS - System.currentTimeMillis()).coerceAtLeast(0)
}

object DeviceIntegrity {
    /** SHA-256 of the official release signing certificate. */
    private const val OFFICIAL_CERT_SHA256 =
        "eeccb19b966feedbc7ad5d696af8031efe65bc7c3b9a75fd8d6ddcd3464c5ff3"

    fun isLikelyRooted(): Boolean {
        val paths = listOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk",
            "/system/bin/.ext/su", "/system/usr/we-need-root/su"
        )
        if (paths.any { File(it).exists() }) return true
        return Build.TAGS?.contains("test-keys") == true
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun isOfficialBuild(c: Context): Boolean = try {
        val pm = c.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        sigs.any { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) == OFFICIAL_CERT_SHA256 }
    } catch (e: Exception) {
        false
    }
}
