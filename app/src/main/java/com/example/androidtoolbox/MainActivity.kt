package com.example.androidtoolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import com.example.androidtoolbox.core.OtpAccount
import com.example.androidtoolbox.core.OtpEngine
import com.example.androidtoolbox.core.OtpStore
import com.example.androidtoolbox.ui.ToolboxApp
import com.google.android.gms.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.gms.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.File

class MainActivity : ComponentActivity() {
    private val accounts = mutableStateListOf<OtpAccount>()
    private lateinit var otpStore: OtpStore
    private var otpStoreError by mutableStateOf<String?>(null)
    private var qrScanned by mutableStateOf<String?>(null)
    private var pendingQr: Bitmap? = null

    private val createQr = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pendingQr
        if (uri != null && bitmap != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            } ?: error("无法打开文件")
        }.onSuccess { toast("二维码已保存") }.onFailure { toast(it.message ?: "保存失败") }
        pendingQr = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        otpStore = OtpStore(this)
        runCatching { accounts.addAll(otpStore.load()) }
            .onFailure {
                otpStoreError = "本地账号无法解密，已暂停修改以保护原有数据。"
                toast("读取 2FA 账号失败：${it.message}")
            }
        window.statusBarColor = android.graphics.Color.rgb(248, 250, 255)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent {
            ToolboxApp(
                accounts = accounts,
                otpStoreError = otpStoreError,
                qrScanned = qrScanned,
                onScanQr = { scan(false) },
                onScanOtp = { scan(true) },
                onAddOtp = ::addAccount,
                onDeleteOtp = ::deleteAccount,
                onSaveQr = { bitmap ->
                    pendingQr = bitmap
                    createQr.launch("toolbox-qr.png")
                },
                onShareQr = ::shareQr,
                onCopy = ::copy,
            )
        }
    }

    private fun scan(forOtp: Boolean) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue.orEmpty()
                if (forOtp) runCatching { OtpEngine.fromUri(value) }
                    .onSuccess(::addAccount)
                    .onFailure { toast(it.message ?: "无法识别 2FA 二维码") }
                else qrScanned = value
            }
            .addOnFailureListener { toast(it.message ?: "扫码失败，请检查 Google Play 服务") }
    }

    private fun addAccount(account: OtpAccount) {
        if (otpStoreError != null) { toast(otpStoreError!!); return }
        runCatching {
            otpStore.save(accounts.toList() + account)
            accounts.add(account)
        }.onSuccess { toast("账号已添加") }
            .onFailure { toast(it.message ?: "添加失败") }
    }

    private fun deleteAccount(account: OtpAccount) {
        if (otpStoreError != null) { toast(otpStoreError!!); return }
        runCatching {
            otpStore.save(accounts.filterNot { it.id == account.id })
            accounts.removeAll { it.id == account.id }
        }.onSuccess { toast("账号已删除") }
            .onFailure { toast(it.message ?: "删除失败") }
    }

    private fun shareQr(bitmap: Bitmap) {
        runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "toolbox-qr.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("二维码", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享二维码"))
        }.onFailure { toast(it.message ?: "分享失败") }
    }

    private fun copy(label: String, value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
