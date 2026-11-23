package com.alshihab.fieldledger

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.alshihab.fieldledger.ui.AppRoot

/** Session lock state: the app re-locks after it has been in the background for a minute. */
object AppLock {
    var locked by mutableStateOf(true)
    var lastStop = 0L
    private const val GRACE_MS = 60_000L

    fun onStart() {
        if (lastStop > 0 && SystemClock.elapsedRealtime() - lastStop > GRACE_MS) locked = true
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

    override fun onStart() {
        super.onStart()
        AppLock.onStart()
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop()
    }
}
