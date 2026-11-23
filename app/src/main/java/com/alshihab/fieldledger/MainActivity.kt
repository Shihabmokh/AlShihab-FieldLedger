package com.alshihab.fieldledger

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.alshihab.fieldledger.ui.AppRoot

/**
 * Session lock. The app locks again (PIN / fingerprint) when it has been in the background,
 * or untouched in the foreground, for longer than the user-chosen timeout.
 */
object AppLock {
    var locked by mutableStateOf(true)
    private var lastStop = 0L
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var skipNextStop = false

    val TIMEOUT_OPTIONS = listOf(
        30_000L to "30 ثانية",
        60_000L to "دقيقة",
        2 * 60_000L to "دقيقتان",
        5 * 60_000L to "5 دقائق",
        10 * 60_000L to "10 دقائق"
    )
    private const val DEFAULT_TIMEOUT = 2 * 60_000L

    fun timeoutMs(c: Context): Long =
        c.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE).getLong("timeout", DEFAULT_TIMEOUT)

    fun setTimeout(c: Context, ms: Long) =
        c.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE).edit().putLong("timeout", ms).apply()

    /** call right before opening another app (contacts picker, dialer, share) so returning does not re-lock */
    fun allowExternal() {
        skipNextStop = true
    }

    fun touch() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        locked = false
        touch()
    }

    fun checkIdle(c: Context) {
        if (!locked && SystemClock.elapsedRealtime() - lastInteraction > timeoutMs(c)) locked = true
    }

    fun onStart(c: Context) {
        if (skipNextStop) {
            skipNextStop = false
        } else if (lastStop > 0 && SystemClock.elapsedRealtime() - lastStop > timeoutMs(c)) {
            locked = true
        }
        touch()
    }

    fun onStop() {
        lastStop = SystemClock.elapsedRealtime()
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot(this) }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppLock.touch()
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop()
    }
}
