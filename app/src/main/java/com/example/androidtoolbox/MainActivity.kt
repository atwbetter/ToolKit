package com.example.androidtoolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.androidtoolbox.core.*
import com.example.androidtoolbox.ui.LocalActions
import com.example.androidtoolbox.ui.ToolboxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var accounts by mutableStateOf<List<OtpAccount>>(emptyList())
    private var localData by mutableStateOf(LocalSnapshot())
    private lateinit var local: LocalStore
    private lateinit var otpStore: OtpStore
    private var otpStoreError by mutableStateOf<String?>(null)
    private var storageError by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var lastBackup by mutableStateOf<String?>(null)
    private var importData by mutableStateOf<BackupData?>(null)
    private var qrScanned by mutableStateOf<String?>(null)
    private var pendingQr: Bitmap? = null
    private var otpMigration by mutableStateOf<OtpMigrationBatch?>(null)
    private var otpManualRequested by mutableStateOf(false)
    companion object { private val ioMutex = Mutex() }

    private val createQr = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pendingQr
        if (uri != null && bitmap != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    ?: error("无法打开文件")
            } }.onSuccess { toast("二维码已保存") }.onFailure { toast("保存失败：${it.message}") }
        }
        pendingQr = null
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runTask {
            val staged = File(cacheDir, "pending-backup.json")
            check(staged.exists()) { "备份已失效，请重新导出" }
            contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                ?: error("无法写入备份文件")
            local.setSetting("last_backup", LocalDate.now().toString())
            staged.delete()
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val pending = File(cacheDir, "pending-import.json")
            pending.delete()
            withContext(Dispatchers.Main) { importData = null }
            contentResolver.openInputStream(uri)?.use { stream ->
                pending.writeBytes(stream.readBytesBounded(BackupCodec.MAX_FILE_BYTES))
            } ?: error("无法读取备份文件")
            withContext(Dispatchers.Main) { toast("已选择文件，请点击预览并输入口令（如有）") }
        }
    }
    private val openQrImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { decodeQrImage(uri) } }
                .onSuccess { qrScanned = it }.onFailure { toast("图片中未找到可识别的二维码") }
            busy = false
        }
    }
    private val openOtpImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) {
                require(uris.size <= 100) { "每次最多选择 100 张图片，可分次继续添加" }
                uris.map(::decodeQrImage)
            } }
            busy = false
            result.onSuccess(::receiveOtpValues).onFailure { toast(it.message ?: "图片中未找到可识别的二维码") }
        }
    }
    private val scanQr = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.getStringExtra(QrScannerActivity.EXTRA_ACTION) == QrScannerActivity.ACTION_IMAGE)
                openQrImage.launch(arrayOf("image/*"))
            else data?.getStringExtra(QrScannerActivity.EXTRA_VALUE)?.let { qrScanned = it }
        }
    }
    private val scanOtp = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            when (data?.getStringExtra(QrScannerActivity.EXTRA_ACTION)) {
                QrScannerActivity.ACTION_IMAGE -> openOtpImages.launch(arrayOf("image/*"))
                QrScannerActivity.ACTION_MANUAL -> if (otpMigration == null) otpManualRequested = true
                    else toast("请先完成或取消当前迁移批次，再手动添加账号")
                else -> data?.getStringExtra(QrScannerActivity.EXTRA_VALUE)?.let { receiveOtpValues(listOf(it)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        local = LocalStore.get(this)
        otpStore = OtpStore(this, local)
        runTask(initial = true) { }
        setContent {
            ToolboxApp(
                accounts = accounts, otpStoreError = otpStoreError, qrScanned = qrScanned,
                local = localData, busy = busy || !ready, storageError = storageError, lastBackup = lastBackup, importData = importData,
                actions = LocalActions(
                    saveNote = { n, saved -> runTask(onSuccess = saved) { local.saveNote(n) } },
                    deleteNote = { id -> runTask { local.deleteNote(id) } },
                    toggleFavorite = { id -> runTask { local.toggleFavorite(id) } },
                    visit = { id -> runTask { local.visit(id) } },
                    clearRecent = { runTask { local.clearRecent() } },
                    export = ::exportBackup,
                    selectImport = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    previewImport = ::previewImport,
                    confirmImport = ::confirmImport,
                    dismissImport = { importData = null },
                ),
                onScanQr = { scan(false) }, onScanOtp = { scan(true) },
                onImageQr = { openQrImage.launch(arrayOf("image/*")) },
                onImageOtp = { openOtpImages.launch(arrayOf("image/*")) },
                onAddOtp = ::addAccount, onDeleteOtp = ::deleteAccount,
                otpManualRequested = otpManualRequested, onOtpManualShown = { otpManualRequested = false },
                otpMigration = otpMigration, onImportOtpText = { receiveOtpValues(listOf(it)) },
                onConfirmOtpMigration = ::confirmOtpMigration, onCancelOtpMigration = { otpMigration = null },
                onSaveQr = { pendingQr = it; createQr.launch("toolbox-qr-${System.currentTimeMillis()}.png") },
                onShareQr = ::shareQr, onCopy = ::copy,
            )
        }
    }

    private fun runTask(initial: Boolean = false, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (!initial && storageError != null) { toast("本地存储尚不可用，请重新打开应用"); return }
        lifecycleScope.launch {
            busy = true
            runCatching {
                ioMutex.withLock {
                    withContext(Dispatchers.IO) {
                        action()
                        val snapshot = local.snapshot()
                        Triple(snapshot, runCatching { otpStore.load() }, local.setting("last_backup"))
                    }.also { (snapshot, loaded, backupDate) ->
                        localData = snapshot; lastBackup = backupDate
                        loaded.onSuccess { accounts = it; otpStoreError = null }
                            .onFailure { otpStoreError = "验证器密钥无法解密，已暂停修改和完整迁移以保护原数据。" }
                    }
                }
            }.onSuccess { onSuccess() }.onFailure {
                if (initial) storageError = "读取本地数据库失败，已暂停修改：${it.message}"
                toast(it.message ?: "操作失败，原数据已保留")
            }
            ready = true; busy = false
        }
    }

    private fun exportBackup(password: String?, includeOtp: Boolean, share: Boolean) {
        runTask {
            require(!includeOtp || otpStoreError == null) { "验证器无法读取，不能导出账号" }
            require(!share || password != null) { "分享迁移文件时请使用口令加密" }
            val content = BackupCodec.encode(BackupData(local.snapshot(), if (includeOtp) otpStore.load() else emptyList()), password)
            if (share) {
                val file = File(File(cacheDir, "shared").apply { mkdirs() }, "toolbox-backup-${System.currentTimeMillis()}.json")
                file.writeText(content, Charsets.UTF_8)
                withContext(Dispatchers.Main) { shareFile(file, "application/json", "分享加密备份") }
            } else {
                File(cacheDir, "pending-backup.json").writeText(content, Charsets.UTF_8)
                withContext(Dispatchers.Main) { createBackup.launch("toolbox-backup-${LocalDate.now()}.json") }
            }
        }
    }

    private fun previewImport(password: String) {
        importData = null
        runTask {
            val file = File(cacheDir, "pending-import.json")
            check(file.exists()) { "请先选择备份文件" }
            val parsed = BackupCodec.decode(file.readText(Charsets.UTF_8), password)
            withContext(Dispatchers.Main) { importData = parsed }
        }
    }

    private fun confirmImport() {
        val incoming = importData ?: return
        runTask {
            require(incoming.accounts.isEmpty() || otpStoreError == null) { "验证器暂不可用，无法导入账号" }
            val merged = if (incoming.accounts.isEmpty()) null else otpStore.encode(BackupCodec.mergeAccounts(otpStore.load(), incoming.accounts))
            local.merge(incoming.local, merged)
            File(cacheDir, "pending-import.json").delete()
            withContext(Dispatchers.Main) { importData = null; toast("已合并备份，保留现有记录") }
        }
    }

    private fun scan(forOtp: Boolean) {
        val intent = Intent(this, QrScannerActivity::class.java).putExtra(QrScannerActivity.EXTRA_OTP, forOtp)
        if (forOtp) scanOtp.launch(intent) else scanQr.launch(intent)
    }

    private fun addAccount(account: OtpAccount) = addAccounts(listOf(account))

    private fun addAccounts(incoming: List<OtpAccount>) {
        runTask {
            check(otpStoreError == null) { otpStoreError.orEmpty() }
            BackupCodec.parseAccounts(BackupCodec.accountsJson(incoming))
            val merged = BackupCodec.mergeAccounts(otpStore.load(), incoming)
            require(merged.size <= 1000) { "最多保存 1000 个验证器账号" }
            otpStore.save(merged)
        }
    }

    private fun receiveOtpValues(values: List<String>) {
        runCatching {
            check(otpStoreError == null && storageError == null) { "本地验证器暂不可用" }
            if (values.isNotEmpty() && values.all { it.trim().startsWith("otpauth://") }) {
                require(otpMigration == null) { "请先完成或取消当前迁移批次" }
                addAccounts(values.map(OtpEngine::fromUri))
            } else {
                var staged = otpMigration ?: OtpMigrationBatch()
                values.forEach { staged = staged.add(OtpMigration.decode(it)) }
                // All selected images must be valid before changing the pending batch.
                otpMigration = staged
            }
        }.onFailure { toast(it.message ?: "2FA 二维码无效") }
    }

    private fun confirmOtpMigration() {
        val batch = otpMigration ?: return
        if (!batch.complete) return
        var added = 0
        runTask(onSuccess = {
            otpMigration = null
            toast("已导入 $added 个账号，跳过 ${batch.accounts.size - added} 个重复账号")
        }) {
            check(otpStoreError == null) { otpStoreError.orEmpty() }
            val incoming = BackupCodec.parseAccounts(BackupCodec.accountsJson(batch.accounts))
            val existing = otpStore.load()
            val merged = BackupCodec.mergeAccounts(existing, incoming)
            require(merged.size <= 1000) { "导入后超过 1000 个账号，请先整理现有账号" }
            added = merged.size - existing.size
            otpStore.save(merged)
        }
    }

    private fun decodeQrImage(uri: android.net.Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        val opts = BitmapFactory.Options()
        while (bounds.outWidth.toLong() / opts.inSampleSize * (bounds.outHeight / opts.inSampleSize) > 4_000_000) opts.inSampleSize *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("无法读取图片")
        return try { QrCodec.decode(bitmap) } finally { bitmap.recycle() }
    }
    private fun deleteAccount(account: OtpAccount) {
        runTask {
            check(otpStoreError == null) { otpStoreError.orEmpty() }
            otpStore.save(otpStore.load().filterNot { it.id == account.id })
        }
    }
    private fun shareQr(bitmap: Bitmap) {
        runCatching {
            val file = File.createTempFile("toolbox-qr-", ".png", File(cacheDir, "shared").apply { mkdirs() })
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            shareFile(file, "image/png", "分享二维码")
        }.onFailure { toast(it.message ?: "分享失败") }
    }
    private fun shareFile(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
    }
    private fun copy(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        if (label in setOf("验证码", "密码", "处理结果")) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        toast("已复制")
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = read(buffer); if (size < 0) break
            require(output.size().toLong() + size <= max) { "文件超过 8 MiB" }; output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}
