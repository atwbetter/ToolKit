package com.example.androidtoolbox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtoolbox.core.Algorithm
import com.example.androidtoolbox.core.CryptoEngine
import com.example.androidtoolbox.core.LocalNote
import com.example.androidtoolbox.core.OtpAccount
import com.example.androidtoolbox.core.OtpEngine
import com.example.androidtoolbox.core.QrCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal val Indigo = Color(0xFF5D62EA)
internal val Blue = Color(0xFF3374EF)
internal val Ink = Color(0xFF263253)
internal val Muted = Color(0xFF64718A)
internal val Canvas = Color(0xFFF8FAFF)
internal val Pale = Color(0xFFE9ECFF)
internal val Gradient = Brush.linearGradient(listOf(Indigo, Blue))

@Composable
internal fun Header(title: String, subtitle: String, back: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, fontSize = 11.sp) }
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun QrPage(
    scannedText: String?,
    onScan: () -> Unit,
    onSave: (Bitmap) -> Unit,
    onShare: (Bitmap) -> Unit,
    onCopy: (String, String) -> Unit,
    onImage: () -> Unit,
    back: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(0) }
    var content by rememberSaveable { mutableStateOf("https://example.com") }
    var kind by rememberSaveable { mutableStateOf("网址") }
    var wifiName by rememberSaveable { mutableStateOf("") }
    var wifiPassword by rememberSaveable { mutableStateOf("") }
    var wifiSecurity by rememberSaveable { mutableStateOf("WPA") }
    var encoded by rememberSaveable { mutableStateOf("https://example.com") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scannedText) { if (scannedText != null) mode = 1 }
    val bitmap = remember(encoded) { runCatching { QrCodec.generate(encoded) }.getOrNull() }
    Column {
        Header("二维码", "生成与解析 · 支持文本、网址、Wi-Fi", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().background(Pale, RoundedCornerShape(13.dp)).padding(4.dp)) {
                    listOf("生成", "扫描解析").forEachIndexed { i, label ->
                        val selected = mode == i
                        Box(Modifier.weight(1f).height(36.dp)
                            .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { mode = i }, contentAlignment = Alignment.Center) {
                            Text(label, color = if (selected) Indigo else Muted, fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            if (mode == 0) {
                item {
                    Text("内容类型", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("网址", "文本", "Wi-Fi").forEach {
                            FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it) })
                        }
                    }
                }
                if (kind == "Wi-Fi") item {
                    Text("Wi-Fi 网络", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(wifiName, { wifiName = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("网络名称（SSID）") })
                    OutlinedTextField(wifiPassword, { wifiPassword = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("密码") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("WPA", "WEP", "nopass").forEach { value ->
                            FilterChip(selected = wifiSecurity == value, onClick = { wifiSecurity = value }, label = { Text(value) })
                        }
                    }
                } else item {
                    Text("输入内容", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 4, shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("输入文字或网址") })
                }
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), "二维码预览", Modifier.size(190.dp))
                            else Text("暂无预览", color = Muted)
                            Spacer(Modifier.height(8.dp))
                            Text("二维码预览", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                item {
                    PrimaryButton("生成二维码", onClick = {
                        val value = if (kind == "Wi-Fi") {
                            fun escape(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(":", "\\:").replace(",", "\\,")
                            if (wifiName.isBlank()) "" else "WIFI:T:$wifiSecurity;S:${escape(wifiName)};P:${escape(wifiPassword)};;"
                        } else content
                        if (value.isBlank()) error = "请输入内容" else {
                            encoded = value
                            error = null
                        }
                    })
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { bitmap?.let(onSave) }, enabled = bitmap != null) { Text("保存图片") }
                        TextButton(onClick = { bitmap?.let(onShare) }, enabled = bitmap != null) { Text("分享") }
                    }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.QrCode2, null, tint = Indigo, modifier = Modifier.size(48.dp))
                            Text("扫描二维码", color = Ink, fontWeight = FontWeight.Bold)
                            Text("相机扫码与图片解析均在本机完成，无需联网或 Google Play 服务。", color = Muted, fontSize = 13.sp)
                            PrimaryButton("选择图片 · 离线解析", onImage)
                            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("相机扫码") }
                        }
                    }
                }
                if (scannedText != null) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("解析结果", color = Ink, fontWeight = FontWeight.Bold)
                            SelectionContainer { Text(scannedText, color = Ink) }
                            TextButton(onClick = { onCopy("二维码内容", scannedText) }) {
                                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("复制内容")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
internal fun CryptoPage(
    onCopy: (String, String) -> Unit,
    onSaveNote: (LocalNote, () -> Unit) -> Unit,
    canSaveNote: Boolean,
    back: () -> Unit,
) {
    var algorithmName by rememberSaveable { mutableStateOf(Algorithm.AES.name) }
    val algorithm = Algorithm.valueOf(algorithmName)
    var decrypt by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var resultNoteId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var savedToNote by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rsaPair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isHash = algorithm == Algorithm.SHA256 || algorithm == Algorithm.SHA512
    val keySize = algorithm.rawKeyBytes
    val needsKey = algorithm == Algorithm.AES || algorithm == Algorithm.RSA || keySize != null
    val keyByteCount = key.toByteArray(Charsets.UTF_8).size
    val invalidKeyLength = keySize != null && keyByteCount != keySize
    fun clearResult() { result = ""; savedToNote = false; error = null }
    Column {
        Header("加解密", "敏感内容默认仅在设备上处理", back, trailing = {
            Text("● 本地处理", color = Color(0xFF229A72), fontSize = 10.sp,
                modifier = Modifier.background(Color(0xFFE3F7EF), RoundedCornerShape(14.dp)).padding(7.dp))
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("算法", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Algorithm.entries.forEach { option ->
                        FilterChip(selected = algorithm == option, onClick = {
                            algorithmName = option.name
                            clearResult()
                            if (option == Algorithm.SHA256 || option == Algorithm.SHA512) decrypt = false
                        }, enabled = !busy, label = { Text(option.label) })
                    }
                }
            }
            if (!isHash) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !decrypt, onClick = { decrypt = false; clearResult() }, enabled = !busy, label = { Text(if (algorithm == Algorithm.BASE64) "编码" else "加密") })
                    FilterChip(selected = decrypt, onClick = { decrypt = true; clearResult() }, enabled = !busy, label = { Text(if (algorithm == Algorithm.BASE64) "解码" else "解密") })
                }
            }
            item {
                Text(if (decrypt) "密文 / 编码内容" else "原文", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(input, { input = it; clearResult() }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    minLines = 4, maxLines = 8, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("输入待处理内容…") })
            }
            if (needsKey) item {
                Text(when {
                    algorithm == Algorithm.RSA -> if (decrypt) "PKCS#8 私钥（PEM）" else "X.509 公钥（PEM）"
                    keySize != null -> "自定义密钥（$keySize 字节）"
                    else -> "口令 / 密钥"
                }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(key, { key = it; clearResult() }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    minLines = if (algorithm == Algorithm.RSA) 3 else 1,
                    maxLines = if (algorithm == Algorithm.RSA) 6 else 2,
                    isError = key.isNotEmpty() && invalidKeyLength,
                    supportingText = if (keySize != null) ({ Text("UTF-8：$keyByteCount / $keySize 字节；1 个英文字母占 1 字节，常用汉字占 3 字节") }) else null,
                    shape = RoundedCornerShape(14.dp), placeholder = { Text(when {
                        algorithm == Algorithm.RSA -> "输入 PEM 密钥"
                        keySize != null -> "输入 $keySize 字节密钥"
                        else -> "输入口令"
                    }) })
                if (algorithm == Algorithm.RSA) TextButton(onClick = {
                    if (rsaPair != null) showKeys = true else scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.Default) { CryptoEngine.generateRsaPair() } }
                            .onSuccess { rsaPair = it; key = if (decrypt) it.second else it.first; clearResult(); showKeys = true }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }, enabled = !busy) { Text(if (rsaPair == null) "生成 2048 位密钥对" else "查看本次生成的密钥对") }
            }
            if (algorithm == Algorithm.AES) item {
                Text("随机生成盐和 IV；解密需要相同口令和完整密文。", color = Muted, fontSize = 11.sp)
            }
            if (keySize != null) item {
                Text("CBC 模式 · PKCS7 填充；直接使用密钥的 UTF-8 字节，不补齐或派生。每次随机生成 IV 并随密文保存，解密请保留完整前缀。", color = Muted, fontSize = 11.sp)
            }
            item {
                PrimaryButton(if (isHash) "计算摘要" else if (decrypt) "开始${if (algorithm == Algorithm.BASE64) "解码" else "解密"}"
                    else "开始${if (algorithm == Algorithm.BASE64) "编码" else "加密"}", enabled = !busy && !invalidKeyLength, onClick = {
                    scope.launch {
                        busy = true
                        clearResult()
                        runCatching { withContext(Dispatchers.Default) { CryptoEngine.process(algorithm, decrypt, input, key) } }
                            .onSuccess { result = it; resultNoteId = UUID.randomUUID().toString() }
                            .onFailure { error = it.message ?: "处理失败，请检查输入与密钥" }
                        busy = false
                    }
                })
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF3FF))) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("输出结果", color = Color(0xFF5368AC), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        SelectionContainer { Text(result.ifBlank { "处理后的内容将显示在这里" }, color = if (result.isBlank()) Muted else Ink, fontSize = 12.sp) }
                        if (result.isNotBlank()) {
                            TextButton(onClick = { onCopy("处理结果", result) }) { Text("复制结果") }
                            if (!decrypt && needsKey) {
                                OutlinedButton(onClick = {
                                    val encrypted = result
                                    onSaveNote(LocalNote(resultNoteId, "${algorithm.label} 加密结果", encrypted)) {
                                        if (result == encrypted) savedToNote = true
                                    }
                                }, enabled = canSaveNote && !busy && !savedToNote, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (savedToNote) "已保存到便签" else "保存到便签")
                                }
                                Text("${if (savedToNote) "可在「离线便签」中查看。" else "保存完整密文；"}密钥与原文不会写入便签。", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item { Text("SHA 摘要为单向运算，不提供解密。当前版本所有算法均在本地处理。", color = Muted, fontSize = 10.sp) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (showKeys && rsaPair != null) AlertDialog(onDismissRequest = { showKeys = false },
        title = { Text("RSA 密钥对") }, text = {
            Column(Modifier.height(350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请妥善保存私钥。离开此页面后，本次生成的密钥对不会自动保存。", color = Muted, fontSize = 12.sp)
                SelectionContainer { Text("公钥\n${rsaPair!!.first}\n\n私钥\n${rsaPair!!.second}", fontSize = 10.sp) }
            }
        }, confirmButton = {
            TextButton(onClick = { key = if (decrypt) rsaPair!!.second else rsaPair!!.first; clearResult(); showKeys = false }) { Text("使用密钥") }
        }, dismissButton = { TextButton(onClick = { showKeys = false }) { Text("关闭") } })
}

@Composable
internal fun OtpPage(
    accounts: List<OtpAccount>,
    storeError: String?,
    busy: Boolean,
    onScan: () -> Unit,
    onAdd: (OtpAccount) -> Unit,
    onDelete: (OtpAccount) -> Unit,
    onCopy: (String, String) -> Unit,
    onImage: () -> Unit,
    onImportText: (String) -> Unit,
    onSaveQr: (Bitmap) -> Unit,
    onShareQr: (Bitmap) -> Unit,
    manualRequested: Boolean,
    onManualShown: () -> Unit,
    onBackup: () -> Unit,
    back: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<OtpAccount?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    val filtered = remember(accounts, query) { OtpEngine.search(accounts, query) }
    LaunchedEffect(manualRequested) {
        if (manualRequested) { showAdd = true; onManualShown() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column {
        Header("身份验证器", "动态验证码 · 离线可用", back, trailing = {
            Box {
                TextButton(onClick = { showActions = true }, enabled = !busy && storeError == null) { Text("管理") }
                DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                    DropdownMenuItem(text = { Text("添加账号 / 扫码导入") },
                        onClick = { showActions = false; showAdd = true })
                    DropdownMenuItem(text = { Text("从二维码图片导入") },
                        onClick = { showActions = false; onImage() })
                    DropdownMenuItem(text = { Text("导出 Google 批量二维码") }, enabled = accounts.isNotEmpty(),
                        onClick = { showActions = false; showExport = true })
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (storeError != null) item { Text(storeError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Pale), shape = RoundedCornerShape(17.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = Indigo, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("${accounts.size} 个账号已保护", color = Ink, fontWeight = FontWeight.Bold)
                            Text("验证码按各账号设置自动更新", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text("搜索平台名称或账号") },
                        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
                    FilledIconButton(onClick = onScan, enabled = !busy && storeError == null,
                        modifier = Modifier.size(56.dp), shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Indigo, contentColor = Color.White)) {
                        Icon(Icons.Outlined.Add, contentDescription = "扫描添加账号")
                    }
                }
                SectionTitle(if (query.isBlank()) "我的账号" else "搜索结果（${filtered.size} / ${accounts.size}）")
            }
            if (accounts.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Security, null, tint = Indigo, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有 2FA 账号", color = Ink, fontWeight = FontWeight.Bold)
                        Text("手动输入密钥，或导入普通 / Google 批量二维码", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            if (accounts.isNotEmpty() && filtered.isEmpty()) item {
                Text("没有匹配的平台或账号，请尝试其他关键词", color = Muted, modifier = Modifier.padding(16.dp))
            }
            items(filtered, key = { it.id }) { account ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Pale, RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                                Text(account.issuer.take(1).ifEmpty { "验" }, color = Indigo, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(account.issuer.ifBlank { account.name }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(account.name, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { deleting = account }, enabled = !busy && storeError == null) {
                                Icon(Icons.Outlined.DeleteOutline, "删除账号", tint = Muted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val code = runCatching { OtpEngine.code(account, now) }.getOrDefault("------")
                            val shown = if (code.length == 6) code.chunked(3).joinToString(" ") else code
                            Text(shown, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp, modifier = Modifier.weight(1f).clickable { onCopy("验证码", code) })
                            Text("${OtpEngine.remaining(account, now)}s", color = Indigo, fontSize = 12.sp,
                                modifier = Modifier.background(Pale, RoundedCornerShape(50)).padding(9.dp))
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                PrimaryButton("＋ 添加账号 / 扫码导入", { showAdd = true }, enabled = !busy && storeError == null)
                OutlinedButton(onClick = onImage, enabled = !busy && storeError == null, modifier = Modifier.fillMaxWidth()) { Text("从二维码图片导入 · 可多选") }
                OutlinedButton(onClick = { showExport = true }, enabled = !busy && storeError == null && accounts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text("导出 Google 批量迁移二维码") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onBackup) { Text("安全备份与迁移 ›", fontSize = 11.sp) }
                }
            }
        }
    }
    if (showExport) OtpMigrationExportDialog(accounts, { showExport = false }, onSaveQr, onShareQr)
    if (showAdd) AddOtpDialog(onDismiss = { showAdd = false }, onScan = { showAdd = false; onScan() },
        onImport = { showAdd = false; onImportText(it) }, onAdd = {
        onAdd(it)
        showAdd = false
    })
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除账号？") },
        text = { Text("删除后，此设备将无法再为该账号生成验证码。请确认已设置其他登录方式。") },
        confirmButton = { TextButton(onClick = { deleting?.let(onDelete); deleting = null }, enabled = !busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
}

@Composable
private fun AddOtpDialog(onDismiss: () -> Unit, onScan: () -> Unit, onImport: (String) -> Unit, onAdd: (OtpAccount) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 2FA 账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(issuer, { issuer = it }, label = { Text("服务名称") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("账号名称") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, label = { Text("Base32 密钥或二维码链接（支持 Google 迁移）") }, minLines = 2, maxLines = 4)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCode2, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫描二维码")
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (secret.trim().startsWith("otpauth-migration://")) {
                onImport(secret)
                return@TextButton
            }
            runCatching {
                if (secret.trim().startsWith("otpauth://")) OtpEngine.fromUri(secret)
                else {
                    require(name.isNotBlank()) { "请输入账号名称" }
                    OtpAccount(UUID.randomUUID().toString(), issuer.trim(), name.trim(), OtpEngine.normalizeSecret(secret))
                }
            }.onSuccess(onAdd).onFailure { error = it.message ?: "密钥格式无效" }
        }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
