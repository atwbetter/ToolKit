package com.example.androidtoolbox.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreApps
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.example.androidtoolbox.core.OtpAccount
import com.example.androidtoolbox.core.OtpEngine
import com.example.androidtoolbox.core.QrCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val Indigo = Color(0xFF5D62EA)
private val Blue = Color(0xFF3374EF)
private val Ink = Color(0xFF263253)
private val Muted = Color(0xFF8996AE)
private val Canvas = Color(0xFFF8FAFF)
private val Pale = Color(0xFFE9ECFF)
private val Gradient = Brush.linearGradient(listOf(Indigo, Blue))

private enum class Page { HOME, QR, CRYPTO, OTP, FAVORITES, HISTORY, PROFILE }

@Composable
fun ToolboxApp(
    accounts: List<OtpAccount>,
    otpStoreError: String?,
    qrScanned: String?,
    onScanQr: () -> Unit,
    onScanOtp: () -> Unit,
    onAddOtp: (OtpAccount) -> Unit,
    onDeleteOtp: (OtpAccount) -> Unit,
    onSaveQr: (Bitmap) -> Unit,
    onShareQr: (Bitmap) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    var pageName by rememberSaveable { mutableStateOf(Page.HOME.name) }
    val page = Page.valueOf(pageName)
    val favorites = remember { mutableStateListOf(Page.QR, Page.CRYPTO, Page.OTP) }
    val history = remember { mutableStateListOf<Page>() }
    fun open(target: Page) {
        pageName = target.name
        if (target in listOf(Page.QR, Page.CRYPTO, Page.OTP)) {
            history.remove(target)
            history.add(0, target)
        }
    }
    BackHandler(page != Page.HOME) { pageName = Page.HOME.name }
    MaterialTheme(colorScheme = lightColorScheme(primary = Indigo, background = Canvas, surface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Canvas) {
            when (page) {
                Page.HOME -> HomePage(history.firstOrNull(), ::open)
                Page.QR -> QrPage(qrScanned, onScanQr, onSaveQr, onShareQr, onCopy) { open(Page.HOME) }
                Page.CRYPTO -> CryptoPage(onCopy) { open(Page.HOME) }
                Page.OTP -> OtpPage(accounts, otpStoreError, onScanOtp, onAddOtp, onDeleteOtp, onCopy) { open(Page.HOME) }
                Page.FAVORITES -> ListPage("收藏", favorites, ::open) { open(Page.HOME) }
                Page.HISTORY -> ListPage("历史", history, ::open) { open(Page.HOME) }
                Page.PROFILE -> ProfilePage { open(Page.HOME) }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, back: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.ArrowBack, "返回", tint = Ink)
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
private fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, fontSize = 11.sp) }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomePage(recent: Page?, open: (Page) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(containerColor = Canvas, bottomBar = {
        NavigationBar(containerColor = Color.White) {
            listOf(
                Triple(Page.HOME, Icons.Outlined.Home, "首页"),
                Triple(Page.FAVORITES, Icons.Outlined.StarBorder, "收藏"),
                Triple(Page.HISTORY, Icons.Outlined.History, "历史"),
                Triple(Page.PROFILE, Icons.Outlined.PersonOutline, "我的")
            ).forEach { (page, icon, label) ->
                NavigationBarItem(selected = page == Page.HOME, onClick = { open(page) },
                    icon = { Icon(icon, label) }, label = { Text(label) })
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { Spacer(Modifier.height(18.dp)) }
            item {
                Text("早上好，开发者", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("今天要处理什么？", color = Muted, fontSize = 13.sp)
            }
            item {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(14.dp), placeholder = { Text("搜索工具或功能") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) })
            }
            if (query.isBlank()) {
                item { SectionTitle(if (recent == null) "推荐工具" else "最近使用", "全部 ›") { open(Page.HISTORY) } }
                item {
                    val target = recent ?: Page.QR
                    Box(Modifier.fillMaxWidth().height(92.dp).background(Gradient, RoundedCornerShape(18.dp))
                        .clickable { open(target) }.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (target) {
                                Page.CRYPTO -> Icons.Outlined.Lock
                                Page.OTP -> Icons.Outlined.Security
                                else -> Icons.Outlined.QrCode2
                            }, null, tint = Color.White, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(15.dp))
                            Column {
                                Text(titleOf(target), color = Color.White, fontWeight = FontWeight.Bold)
                                Text("点击继续使用", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item { SectionTitle(if (query.isBlank()) "常用工具" else "搜索结果") }
            val tools = listOf(Page.QR, Page.CRYPTO, Page.OTP).filter { query.isBlank() || titleOf(it).contains(query, true) }
            if (tools.isEmpty()) item { Text("没有找到相关工具", color = Muted) }
            else items(tools.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item -> ToolTile(item, Modifier.weight(1f)) { open(item) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (query.isBlank()) item { ToolTile(Page.PROFILE, Modifier.fillMaxWidth()) { open(Page.PROFILE) } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun titleOf(page: Page) = when (page) {
    Page.QR -> "二维码"
    Page.CRYPTO -> "加解密"
    Page.OTP -> "身份验证器"
    Page.PROFILE -> "更多工具"
    else -> page.name
}

@Composable
private fun ToolTile(page: Page, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val icon = when (page) {
        Page.QR -> Icons.Outlined.QrCode2
        Page.CRYPTO -> Icons.Outlined.Lock
        Page.OTP -> Icons.Outlined.Security
        else -> Icons.Outlined.MoreApps
    }
    val desc = when (page) {
        Page.QR -> "生成 · 扫描"
        Page.CRYPTO -> "AES · RSA · 摘要"
        Page.OTP -> "动态 2FA 验证码"
        else -> "隐私与设置"
    }
    Card(onClick = onClick, modifier = modifier.height(120.dp), shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(15.dp)) {
            Box(Modifier.size(38.dp).background(Pale, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Indigo, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(titleOf(page), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(desc, color = Muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ListPage(title: String, pages: List<Page>, open: (Page) -> Unit, back: () -> Unit) {
    Column {
        Header(title, "常用工具快捷入口", back)
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pages.isEmpty()) Text("暂无记录", color = Muted)
            pages.forEach { ToolTile(it, Modifier.fillMaxWidth()) { open(it) } }
        }
    }
}

@Composable
private fun ProfilePage(back: () -> Unit) {
    Column {
        Header("我的", "工具箱 · 0.1.0", back)
        Card(Modifier.fillMaxWidth().padding(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("隐私说明", fontWeight = FontWeight.Bold, color = Ink)
                Text("加解密和动态验证码在设备本地处理。2FA 密钥使用 Android Keystore 加密保存；不会上传服务器。扫码功能由 Google Play 服务提供。", color = Muted, fontSize = 13.sp)
                HorizontalDivider()
                Text("当前版本聚焦二维码、加解密和 2FA 三项工具。", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun QrPage(
    scannedText: String?,
    onScan: () -> Unit,
    onSave: (Bitmap) -> Unit,
    onShare: (Bitmap) -> Unit,
    onCopy: (String, String) -> Unit,
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
                            Text("调用设备扫码界面，识别二维码中的文本或链接。", color = Muted, fontSize = 13.sp)
                            PrimaryButton("开始扫描", onScan)
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
private fun CryptoPage(onCopy: (String, String) -> Unit, back: () -> Unit) {
    var algorithmName by rememberSaveable { mutableStateOf(Algorithm.AES.name) }
    val algorithm = Algorithm.valueOf(algorithmName)
    var decrypt by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rsaPair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isHash = algorithm == Algorithm.SHA256 || algorithm == Algorithm.SHA512
    val needsKey = algorithm == Algorithm.AES || algorithm == Algorithm.RSA
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
                            result = ""
                            error = null
                            if (option == Algorithm.SHA256 || option == Algorithm.SHA512) decrypt = false
                        }, label = { Text(option.label) })
                    }
                }
            }
            if (!isHash) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !decrypt, onClick = { decrypt = false; result = "" }, label = { Text(if (algorithm == Algorithm.BASE64) "编码" else "加密") })
                    FilterChip(selected = decrypt, onClick = { decrypt = true; result = "" }, label = { Text(if (algorithm == Algorithm.BASE64) "解码" else "解密") })
                }
            }
            item {
                Text(if (decrypt) "密文 / 编码内容" else "原文", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 4, maxLines = 8, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("输入待处理内容…") })
            }
            if (needsKey) item {
                Text(if (algorithm == Algorithm.RSA) {
                    if (decrypt) "PKCS#8 私钥（PEM）" else "X.509 公钥（PEM）"
                } else "口令 / 密钥", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (algorithm == Algorithm.RSA) 3 else 1,
                    maxLines = if (algorithm == Algorithm.RSA) 6 else 2,
                    shape = RoundedCornerShape(14.dp), placeholder = { Text("输入${if (algorithm == Algorithm.RSA) "PEM 密钥" else "口令"}") })
                if (algorithm == Algorithm.RSA) TextButton(onClick = {
                    if (rsaPair != null) showKeys = true else scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.Default) { CryptoEngine.generateRsaPair() } }
                            .onSuccess { rsaPair = it; key = if (decrypt) it.second else it.first; showKeys = true }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }) { Text(if (rsaPair == null) "生成 2048 位密钥对" else "查看本次生成的密钥对") }
            }
            if (algorithm == Algorithm.AES) item {
                Text("随机生成盐和 IV；解密需要相同口令和完整密文。", color = Muted, fontSize = 11.sp)
            }
            item {
                PrimaryButton(if (isHash) "计算摘要" else if (decrypt) "开始${if (algorithm == Algorithm.BASE64) "解码" else "解密"}"
                    else "开始${if (algorithm == Algorithm.BASE64) "编码" else "加密"}", enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching { withContext(Dispatchers.Default) { CryptoEngine.process(algorithm, decrypt, input, key) } }
                            .onSuccess { result = it }
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
                        if (result.isNotBlank()) TextButton(onClick = { onCopy("处理结果", result) }) { Text("复制结果") }
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
            TextButton(onClick = { key = if (decrypt) rsaPair!!.second else rsaPair!!.first; showKeys = false }) { Text("使用密钥") }
        }, dismissButton = { TextButton(onClick = { showKeys = false }) { Text("关闭") } })
}

@Composable
private fun OtpPage(
    accounts: List<OtpAccount>,
    storeError: String?,
    onScan: () -> Unit,
    onAdd: (OtpAccount) -> Unit,
    onDelete: (OtpAccount) -> Unit,
    onCopy: (String, String) -> Unit,
    back: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<OtpAccount?>(null) }
    var showBackupInfo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column {
        Header("身份验证器", "动态验证码 · 离线可用", back)
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
            item { SectionTitle("我的账号") }
            if (accounts.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Security, null, tint = Indigo, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有 2FA 账号", color = Ink, fontWeight = FontWeight.Bold)
                        Text("手动输入密钥，或扫描 otpauth 二维码", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            items(accounts, key = { it.id }) { account ->
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
                            IconButton(onClick = { deleting = account }) {
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
                PrimaryButton("＋ 添加账号", { showAdd = true }, enabled = storeError == null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { showBackupInfo = true }) { Text("安全备份与迁移 ›", fontSize = 11.sp) }
                }
            }
        }
    }
    if (showAdd) AddOtpDialog(onDismiss = { showAdd = false }, onScan = { showAdd = false; onScan() }, onAdd = {
        onAdd(it)
        showAdd = false
    })
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除账号？") },
        text = { Text("删除后，此设备将无法再为该账号生成验证码。请确认已设置其他登录方式。") },
        confirmButton = { TextButton(onClick = { deleting?.let(onDelete); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    if (showBackupInfo) AlertDialog(onDismissRequest = { showBackupInfo = false }, title = { Text("安全备份与迁移") },
        text = { Text("当前版本尚未提供导出功能。添加账号时，请同时保存服务提供方给出的恢复码。") },
        confirmButton = { TextButton(onClick = { showBackupInfo = false }) { Text("知道了") } })
}

@Composable
private fun AddOtpDialog(onDismiss: () -> Unit, onScan: () -> Unit, onAdd: (OtpAccount) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 2FA 账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(issuer, { issuer = it }, label = { Text("服务名称") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("账号名称") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, label = { Text("Base32 密钥或 otpauth 链接") }, minLines = 2, maxLines = 4)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCode2, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫描二维码")
                }
            }
        },
        confirmButton = { TextButton(onClick = {
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
