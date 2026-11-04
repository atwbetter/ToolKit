package com.example.androidtoolbox

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.androidtoolbox.core.OtpEngine
import com.example.androidtoolbox.core.OtpMigration
import com.example.androidtoolbox.ui.CameraScannerScreen
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OTP = "otp_only"
        const val EXTRA_VALUE = "qr_value"
        const val EXTRA_ACTION = "qr_action"
        const val ACTION_IMAGE = "image"
        const val ACTION_MANUAL = "manual"
    }
    private val finished = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val otpOnly = intent.getBooleanExtra(EXTRA_OTP, false)
        if (otpOnly) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CameraScannerScreen(otpOnly = otpOnly, close = ::finish,
                onImage = { complete(Intent().putExtra(EXTRA_ACTION, ACTION_IMAGE)) },
                onManual = { complete(Intent().putExtra(EXTRA_ACTION, ACTION_MANUAL)) },
                onScan = { value ->
                    val error = if (otpOnly) runCatching {
                        if (value.trim().startsWith("otpauth-migration://")) OtpMigration.decode(value)
                        else OtpEngine.fromUri(value)
                    }.exceptionOrNull()?.let { it.message ?: "不是可导入的 2FA 二维码" } else null
                    if (error == null) complete(Intent().putExtra(EXTRA_VALUE, value))
                    error
                })
        }
    }

    private fun complete(result: Intent) {
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (finished.compareAndSet(false, true)) { setResult(RESULT_OK, result); finish() }
    }
}
