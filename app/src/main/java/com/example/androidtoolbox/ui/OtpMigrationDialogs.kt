package com.example.androidtoolbox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.androidtoolbox.core.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OtpMigrationImportDialog(
    batch: OtpMigrationBatch, busy: Boolean, scan: () -> Unit, images: () -> Unit,
    confirm: () -> Unit, cancel: () -> Unit,
) {
    AlertDialog(onDismissRequest = { if (!busy) cancel() }, title = { Text("Google 批量导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已读取 ${batch.received} / ${batch.total} 张二维码，共 ${batch.accounts.size} 个账号")
                if (!batch.complete) Text("请继续扫描或选择同一批次剩余的二维码。全部读取后才能导入。", color = Muted, fontSize = 12.sp)
                else Text("确认后合并到本机，已存在的相同账号会自动跳过。", color = Muted, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(batch.accounts, key = { it.id }) { account ->
                        Column {
                            Text(account.issuer.ifBlank { "未命名平台" }, color = Ink)
                            Text(account.name, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (!batch.complete) {
                    OutlinedButton(scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("继续扫描") }
                    OutlinedButton(images, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("继续选择二维码图片") }
                }
            }
        },
        confirmButton = { TextButton(confirm, enabled = batch.complete && !busy) { Text("确认导入") } },
        dismissButton = { TextButton(cancel, enabled = !busy) { Text("取消导入") } })
}

@Composable
internal fun OtpMigrationExportDialog(
    accounts: List<OtpAccount>, dismiss: () -> Unit, save: (Bitmap) -> Unit, share: (Bitmap) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pages by remember { mutableStateOf(emptyList<String>()) }
    var page by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberForegroundScope()
    val previewScroll = rememberScrollState()
    val filtered = remember(accounts, query) { OtpEngine.search(accounts, query) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pages, page, lifecycle) {
        val content = pages.getOrNull(page) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                bitmap = withContext(Dispatchers.Default) { QrCodec.generate(content, 1024) }
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "生成二维码失败，请减少所选账号或使用加密备份"
            } finally { bitmap = null }
        }
    }
    AlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text("导出 Google 迁移二维码") },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        text = {
            Column(
                modifier = Modifier.heightIn(max = 490.dp)
                    .then(if (pages.isNotEmpty()) Modifier.verticalScroll(previewScroll) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("二维码包含账号密钥，仅交给你信任的设备。", color = Muted, fontSize = 12.sp)
                if (pages.isEmpty()) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索平台或账号") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 个", Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(onClick = { selected = selected + filtered.filter { OtpMigration.exportIssue(it) == null }.map { it.id } }) { Text("全选结果") }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (filtered.isEmpty()) item { Text("没有匹配的账号", color = Muted) }
                        items(filtered, key = { it.id }) { account ->
                            val issue = OtpMigration.exportIssue(account)
                            fun toggle() { selected = if (account.id in selected) selected - account.id else selected + account.id }
                            Row(Modifier.fillMaxWidth().clickable(enabled = issue == null && !working) { toggle() }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(account.id in selected, { toggle() }, enabled = issue == null && !working)
                                Column(Modifier.weight(1f)) {
                                    Text(account.issuer.ifBlank { account.name }, fontSize = 13.sp)
                                    Text(account.name, color = Muted, fontSize = 11.sp)
                                    if (issue != null) Text("$issue；可用加密备份迁移", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    Text("第 ${page + 1} / ${pages.size} 张 · 共 ${selected.size} 个账号", color = Ink)
                    Text("在 Google Authenticator 中选择「转移账号 → 导入账号」，依次扫描全部二维码。", color = Muted, fontSize = 12.sp)
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "Google 批量迁移二维码 ${page + 1}",
                        Modifier.fillMaxWidth().padding(top = 12.dp).aspectRatio(1f))
                    else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { bitmap = null; page-- }, enabled = page > 0) { Text("上一张") }
                        TextButton(onClick = { bitmap = null; page++ }, enabled = page < pages.lastIndex) { Text("下一张") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { bitmap?.let(save) }, enabled = bitmap != null) { Text("保存本张图片") }
                        TextButton(onClick = { bitmap?.let(share) }, enabled = bitmap != null) { Text("分享本张图片") }
                    }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (pages.isEmpty()) TextButton(onClick = {
                working = true; error = null
                scope.launch {
                    try {
                        val ids = selected
                        pages = withContext(Dispatchers.Default) { OtpMigration.encode(accounts.filter { it.id in ids }) }
                        page = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "导出失败"
                    } finally { working = false }
                }
            }, enabled = selected.isNotEmpty() && !working) { Text(if (working) "正在生成…" else "生成二维码") }
            else TextButton(onClick = dismiss) { Text("完成") }
        },
        dismissButton = {
            if (pages.isEmpty()) TextButton(onClick = dismiss, enabled = !working) { Text("取消") }
            else TextButton(onClick = { pages = emptyList(); bitmap = null; error = null }) { Text("重新选择") }
        })
}
