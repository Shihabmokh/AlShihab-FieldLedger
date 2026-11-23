package com.alshihab.fieldledger.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alshihab.fieldledger.AppLock
import com.alshihab.fieldledger.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Biometric {
    fun available(c: Context): Boolean =
        BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String = "فتح الدفتر الميداني", onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("استخدام الرمز")
            .build()
        prompt.authenticate(info)
    }
}

@Composable
fun LockScreen(activity: FragmentActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val setupMode = remember { !PinManager.isSet(ctx) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bioOn = remember { !setupMode && PinManager.biometricEnabled(ctx) && Biometric.available(ctx) }

    LaunchedEffect(Unit) {
        if (bioOn) Biometric.prompt(activity) { AppLock.unlock() }
    }

    val title = when {
        !setupMode -> "أدخل الرمز السري"
        firstPin == null -> "أنشئ رمزاً سرياً (${PinManager.MIN_LEN} إلى ${PinManager.MAX_LEN} أرقام)"
        else -> "أعد إدخال الرمز للتأكيد"
    }

    fun submit() {
        if (busy) return
        if (pin.length < PinManager.MIN_LEN) {
            error = "الرمز يجب أن يكون ${PinManager.MIN_LEN} أرقام على الأقل"
            return
        }
        if (setupMode) {
            if (firstPin == null) {
                firstPin = pin; pin = ""; error = null
            } else if (firstPin == pin) {
                busy = true
                val chosen = pin
                scope.launch {
                    withContext(Dispatchers.Default) { PinManager.setPin(ctx, chosen) }
                    busy = false
                    AppLock.unlock()
                }
            } else {
                firstPin = null; pin = ""; error = "الرمزان غير متطابقين، حاول مرة أخرى"
            }
        } else {
            val wait = PinManager.lockoutRemaining(ctx)
            if (wait > 0) {
                error = "محاولات كثيرة خاطئة. انتظر ${wait / 1000 + 1} ثانية"
                pin = ""
                return
            }
            busy = true
            val entered = pin
            scope.launch {
                val ok = withContext(Dispatchers.Default) { PinManager.verify(ctx, entered) }
                busy = false
                if (ok) {
                    AppLock.unlock()
                } else {
                    pin = ""
                    val w = PinManager.lockoutRemaining(ctx)
                    error = if (w > 0) "محاولات كثيرة خاطئة. انتظر ${w / 1000 + 1} ثانية" else "الرمز غير صحيح"
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.alshihab.fieldledger.R.drawable.logo_sh),
                contentDescription = "شعار مكتب الشهاب",
                modifier = Modifier.size(width = 150.dp, height = 117.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("الدفتر الميداني – الشهاب", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Brand)
            Text("مكتب الشهاب للمقاولات العامة", fontSize = 13.sp, color = Accent)
            Spacer(Modifier.height(8.dp))
            Text(title, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(maxOf(PinManager.MIN_LEN, pin.length)) { i ->
                        Box(
                            Modifier
                                .size(16.dp)
                                .background(if (i < pin.length) Brand else Color(0xFFCFD8DC), CircleShape)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(error ?: " ", color = Bad, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { d ->
                                PadKey(onClick = { if (pin.length < PinManager.MAX_LEN) { pin += d; error = null } }) {
                                    Text(d, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadKey(onClick = { pin = pin.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "حذف")
                        }
                        PadKey(onClick = { if (pin.length < PinManager.MAX_LEN) { pin += "0"; error = null } }) {
                            Text("0", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        PadKey(onClick = { submit() }) {
                            Icon(Icons.Filled.Check, contentDescription = "تأكيد")
                        }
                    }
                }
            }
            if (bioOn) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { Biometric.prompt(activity) { AppLock.unlock() } }) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text("  الدخول بالبصمة")
                }
            }
        }
    }
}

@Composable
private fun PadKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.size(76.dp), shape = CircleShape) {
        content()
    }
}
