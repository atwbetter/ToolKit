package com.example.androidtoolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.androidtoolbox.core.*
import com.example.androidtoolbox.ui.LocalActions
import com.example.androidtoolbox.ui.MAX_CLIPBOARD_CHARS
import com.example.androidtoolbox.ui.ToolboxApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var accounts by mutableStateOf<List<OtpAccount>>(emptyList())
    private var localData by mutableStateOf(LocalSummary())
    private var notesVersion by mutableIntStateOf(0)
    private var otpCount by mutableIntStateOf(0)
    private var page = "HOME"
    private var visible = false
    private var otpRead: Job? = null
    private val visibleTasks = mutableSetOf<Job>()
    private var activeTasks = 0

    private enum class Change { NONE, INITIAL, NOTES, FAVORITES, RECENT, BACKUP_DATE, OTP, IMPORT }
    private lateinit var local: LocalStore
    private val otpStore by lazy { OtpStore(applicationContext, local) }
    private var otpStoreError by mutableStateOf<String?>(null)
    private var storageError by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var lastBackup by mutableStateOf<String?>(null)
    private var importData by mutableStateOf<BackupData?>(null)
    private var qrScanned by mutableStateOf<CodeResult?>(null)
    private var otpMigration by mutableStateOf<OtpMigrationBatch?>(null)
    private var otpManualRequested by mutableStateOf(false)
    companion object { private val ioMutex = Mutex() }

    private val createQr = createStagedFile("image/png", "pending-code.png", "码图已保存")
    private val createText = createStagedFile("text/plain", "pending-result.txt", "文本已保存")

    private fun createStagedFile(mime: String, name: String, saved: String) =
        registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            runTask(finishWrite = true, onSuccess = { if (uri != null && visible) toast(saved) }) {
                val file = File(cacheDir, name)
                try {
                    if (uri != null) {
                        check(file.isFile) { "临时结果已失效，请重新生成" }
                        contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: error("无法打开文件")
                    }
                } finally { file.delete() }
            }
        }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        runTask(change = if (uri == null) Change.NONE else Change.BACKUP_DATE, finishWrite = true) {
            val staged = File(cacheDir, "pending-backup.json")
            try {
                if (uri != null) {
                    check(staged.isFile) { "备份已失效，请重新导出" }
                    contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { it.copyTo(output) } }
                        ?: error("无法写入备份文件")
                    local.setSetting("last_backup", LocalDate.now().toString())
                }
            } finally { staged.delete() }
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importData = null
            runTask(onSuccess = { toast("已选择文件，请点击预览并输入口令（如有）") }) {
                val pending = File(cacheDir, "pending-import.json")
                pending.delete()
                var complete = false
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        pending.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= BackupCodec.MAX_FILE_BYTES) { "文件超过 8 MiB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("无法读取备份文件")
                    complete = true
                } finally { if (!complete) pending.delete() }
            }
        }
    }
    private val openQrImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val result = decodeCodeImage(uri)
            withContext(Dispatchers.Main) { if (page == "QR") qrScanned = result }
        }
    }
    private val openOtpImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            var values = emptyList<String>()
            runTask(onSuccess = { if (page == "OTP") receiveOtpValues(values) }) {
                require(uris.size <= 100) { "每次最多选择 100 张图片，可分次继续添加" }
                values = uris.map {
                    currentCoroutineContext().ensureActive()
                    decodeCodeImage(it, qrOnly = true).text
                }
            }
        }
    }
    private val scanQr = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.getStringExtra(QrScannerActivity.EXTRA_ACTION) == QrScannerActivity.ACTION_IMAGE)
                openQrImage.launch(arrayOf("image/*"))
            else data?.getStringExtra(QrScannerActivity.EXTRA_VALUE)?.let {
                val format = runCatching { com.google.zxing.BarcodeFormat.valueOf(data.getStringExtra(QrScannerActivity.EXTRA_FORMAT).orEmpty()) }
                    .getOrDefault(com.google.zxing.BarcodeFormat.QR_CODE)
                qrScanned = CodeResult(it, format)
            }
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
        setContent {
            ToolboxApp(
                accounts = accounts, otpStoreError = otpStoreError, qrScanned = qrScanned,
                local = localData, notesVersion = notesVersion, otpCount = otpCount, onPageChanged = ::showPage,
                busy = busy || !ready, storageError = storageError, lastBackup = lastBackup, importData = importData,
                actions = LocalActions(
                    saveNote = { n, saved -> runTask(Change.NOTES, finishWrite = true, onSuccess = saved) { local.saveNote(n) } },
                    deleteNote = { id -> runTask(Change.NOTES, finishWrite = true) { local.deleteNote(id) } },
                    toggleFavorite = { id -> runTask(Change.FAVORITES, finishWrite = true) { local.toggleFavorite(id) } },
                    visit = { id -> runTask(Change.RECENT, finishWrite = true, showBusy = false) { local.visit(id) } },
                    clearRecent = { runTask(Change.RECENT, finishWrite = true) { local.clearRecent() } },
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
                onSaveQr = ::saveQr,
                onShareQr = ::shareQr, onCopy = ::copy, onSaveText = ::saveText,
            )
        }
    }

    override fun onStart() {
        visible = true
        super.onStart()
        if (!ready) runTask(Change.INITIAL) {
            pruneTemporaryFiles(cacheDir, "pending-", 4, 24L * 1024 * 1024)
        }
        loadOtpForPage()
    }

    override fun onStop() {
        visible = false
        visibleTasks.toList().forEach { it.cancel() }
        accounts = emptyList()
        super.onStop()
    }

    private fun showPage(next: String) {
        if (next == page) return
        page = next
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        if (next == "OTP" || next == "CRYPTO") window.addFlags(secure) else window.clearFlags(secure)
        if (next != "OTP") {
            accounts = emptyList()
            otpMigration = null
        }
        if (next != "QR") qrScanned = null
        if (next != "PROFILE") importData = null
        loadOtpForPage()
    }

    private fun loadOtpForPage() {
        otpRead?.cancel()
        if (visible && page in setOf("OTP", "PROFILE")) otpRead = runTask(Change.OTP)
    }

    private fun runTask(
        change: Change = Change.NONE,
        finishWrite: Boolean = false,
        showBusy: Boolean = true,
        needsStorage: Boolean = change != Change.NONE,
        onSuccess: () -> Unit = {},
        action: suspend () -> Unit = {},
    ): Job? {
        if (!visible) return null
        if (needsStorage && change != Change.INITIAL && storageError != null) {
            toast("本地存储尚不可用，请重新打开应用")
            return null
        }
        val job = lifecycleScope.launch {
            if (showBusy) { activeTasks++; busy = true }
            try {
                ioMutex.withLock {
                    check(!needsStorage || change == Change.INITIAL || storageError == null) { "本地存储尚不可用，请重新打开应用" }
                    if (!visible && !showBusy) return@withLock
                    withContext(Dispatchers.IO) { action() }
                    refresh(change)
                }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                if (visible) toast("内存不足，请缩小输入或分批处理")
            } catch (failure: Exception) {
                if (change == Change.INITIAL) storageError = "读取本地数据库失败，已暂停修改：\${failure.message}"
                if (visible) toast(failure.message ?: "操作失败，请重试")
            } finally {
                if (change == Change.INITIAL && visible) ready = true
                if (showBusy) { activeTasks--; busy = activeTasks > 0 }
            }
        }
        if (!finishWrite) {
            visibleTasks.add(job)
            job.invokeOnCompletion { visibleTasks.remove(job) }
        }
        return job
    }

    private suspend fun refresh(change: Change) {
        if (change == Change.INITIAL || change == Change.IMPORT) {
            localData = withContext(Dispatchers.IO) { LocalSummary(local.noteCount(), local.favorites(), local.recent()) }
            lastBackup = withContext(Dispatchers.IO) { local.setting("last_backup") }
        }
        if (change == Change.NOTES) localData = localData.copy(noteCount = withContext(Dispatchers.IO) { local.noteCount() })
        if (change == Change.NOTES || change == Change.IMPORT) notesVersion++
        if (change == Change.FAVORITES) localData = localData.copy(favorites = withContext(Dispatchers.IO) { local.favorites() })
        if (change == Change.RECENT) localData = localData.copy(recent = withContext(Dispatchers.IO) { local.recent() })
        if (change == Change.BACKUP_DATE) lastBackup = withContext(Dispatchers.IO) { local.setting("last_backup") }
        if ((change == Change.OTP || change == Change.IMPORT) && visible && page in setOf("OTP", "PROFILE")) {
            val loaded = withContext(Dispatchers.IO) { runCatching { otpStore.load() } }
            loaded.onSuccess {
                if (visible && page == "OTP") accounts = it
                otpCount = it.size
                otpStoreError = null
            }.onFailure {
                accounts = emptyList()
                otpStoreError = "验证器密钥无法解密，已暂停修改和完整迁移以保护原数据。"
            }
        }
    }

    private fun exportBackup(password: String?, includeOtp: Boolean, share: Boolean) {
        runTask(needsStorage = true) {
            require(!includeOtp || otpStoreError == null) { "验证器无法读取，不能导出账号" }
            require(!share || password != null) { "分享迁移文件时请使用口令加密" }
            val content = BackupCodec.encode(BackupData(local.backupSnapshot(), if (includeOtp) otpStore.load() else emptyList()), password)
            if (share) {
                stageShare(".json", "application/json", "分享加密备份") { it.writeText(content, Charsets.UTF_8) }
            } else {
                val file = File(cacheDir, "pending-backup.json")
                stageForPicker(file, { file.writeText(content, Charsets.UTF_8) }) {
                    createBackup.launch("toolbox-backup-${LocalDate.now()}.json")
                }
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
        runTask(Change.IMPORT, finishWrite = true) {
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
        runTask(Change.OTP, finishWrite = true) {
            check(otpStoreError == null) { otpStoreError.orEmpty() }
            BackupCodec.parseAccounts(BackupCodec.accountsJson(incoming))
            val merged = BackupCodec.mergeAccounts(otpStore.load(), incoming)
            require(merged.size <= 1000) { "最多保存 1000 个验证器账号" }
            otpStore.save(merged)
        }
    }

    private fun receiveOtpValues(values: List<String>) {
        if (otpStoreError != null) {
            toast("本地验证器暂不可用")
            return
        }
        val currentBatch = otpMigration
        if (values.isNotEmpty() && values.all { it.trim().startsWith("otpauth://") }) {
            var parsed = emptyList<OtpAccount>()
            runTask(needsStorage = true, onSuccess = { if (page == "OTP") addAccounts(parsed) }) {
                require(currentBatch == null) { "请先完成或取消当前迁移批次" }
                parsed = values.map(OtpEngine::fromUri)
            }
        } else {
            var staged = currentBatch ?: OtpMigrationBatch()
            runTask(needsStorage = true, onSuccess = { if (page == "OTP") otpMigration = staged }) {
                for (value in values) {
                    currentCoroutineContext().ensureActive()
                    staged = staged.add(OtpMigration.decode(value))
                }
            }
        }
    }

    private fun confirmOtpMigration() {
        val batch = otpMigration ?: return
        if (!batch.complete) return
        var added = 0
        runTask(Change.OTP, finishWrite = true, onSuccess = {
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

    private fun decodeCodeImage(uri: android.net.Uri, qrOnly: Boolean = false): CodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, 4096, 4_000_000)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("无法读取图片")
        return try { BarcodeCodec.decode(bitmap, qrOnly) } finally { bitmap.recycle() }
    }
    private fun deleteAccount(account: OtpAccount) {
        runTask(Change.OTP, finishWrite = true) {
            check(otpStoreError == null) { otpStoreError.orEmpty() }
            otpStore.save(otpStore.load().filterNot { it.id == account.id })
        }
    }
    private fun saveText(value: String) {
        if (busy) return
        runTask {
            val file = File(cacheDir, "pending-result.txt")
            stageForPicker(file, { file.writeText(value, Charsets.UTF_8) }) {
                createText.launch("toolbox-result-${System.currentTimeMillis()}.txt")
            }
        }
    }

    private fun saveQr(bitmap: Bitmap) {
        if (busy) return
        runTask {
            val file = File(cacheDir, "pending-code.png")
            stageForPicker(file, { writeQr(bitmap, file) }) {
                createQr.launch("toolbox-code-${System.currentTimeMillis()}.png")
            }
        }
    }

    private suspend fun stageForPicker(file: File, write: () -> Unit, launchPicker: () -> Unit) {
        var launched = false
        try {
            write()
            withContext(Dispatchers.Main) {
                launchPicker()
                launched = true
            }
        } finally { if (!launched) file.delete() }
    }

    private fun shareQr(bitmap: Bitmap) {
        if (busy) return
        runTask { stageShare(".png", "image/png", "分享码图") { writeQr(bitmap, it) } }
    }

    private fun writeQr(bitmap: Bitmap, file: File) {
        file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private suspend fun stageShare(extension: String, mime: String, title: String, write: (File) -> Unit) {
        val directory = File(cacheDir, "shared")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建临时目录" }
        val file = File.createTempFile("toolbox-", extension, directory)
        stageForPicker(file, {
            write(file)
            pruneTemporaryFiles(directory, "toolbox-", 12, 64L * 1024 * 1024, setOf(file.name))
        }) { shareFile(file, mime, title) }
    }

    private fun shareFile(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
    }
    private fun copy(label: String, value: String) {
        if (value.length > MAX_CLIPBOARD_CHARS) {
            toast("内容过长，无法复制到系统剪贴板")
            return
        }
        val clip = ClipData.newPlainText(label, value)
        if (label in setOf("验证码", "密码", "便签正文", "处理结果", "图片 Base64", "EXIF")) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            toast("已复制")
        } catch (_: RuntimeException) {
            toast("系统剪贴板无法复制此内容")
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
