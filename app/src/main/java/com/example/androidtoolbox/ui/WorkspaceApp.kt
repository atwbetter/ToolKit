package com.example.androidtoolbox.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtoolbox.core.*

data class LocalActions(
    val saveNote: (LocalNote, () -> Unit) -> Unit,
    val deleteNote: (String) -> Unit,
    val toggleFavorite: (String) -> Unit,
    val visit: (String) -> Unit,
    val clearRecent: () -> Unit,
    val export: (String?, Boolean, Boolean) -> Unit,
    val selectImport: () -> Unit,
    val previewImport: (String) -> Unit,
    val confirmImport: () -> Unit,
    val dismissImport: () -> Unit,
)

private data class Tool(val id: String, val name: String, val desc: String, val category: String, val icon: ImageVector)
private val catalog = listOf(
    Tool("NOTES", "离线便签", "记录 · 搜索 · 置顶", "效率", Icons.Outlined.Description),
    Tool("JSON", "JSON 工具", "格式化 · 压缩 · 校验", "开发", Icons.Outlined.Code),
    Tool("TEXT", "文本处理", "统计 · 去重 · 排序", "效率", Icons.Outlined.TextFields),
    Tool("UNITS", "单位换算", "长度 · 质量 · 温度 · 存储", "效率", Icons.Outlined.SwapHoriz),
    Tool("TIME", "时间戳", "日期与秒 / 毫秒互转", "开发", Icons.Outlined.Schedule),
    Tool("PASSWORD", "密码生成", "安全随机 · 自定义字符", "安全", Icons.Outlined.Key),
    Tool("QR", "二维码", "生成 · 离线图片解析", "效率", Icons.Outlined.QrCode2),
    Tool("CRYPTO", "加解密", "AES · RSA · 摘要", "安全", Icons.Outlined.Lock),
    Tool("OTP", "身份验证器", "动态 2FA 验证码", "安全", Icons.Outlined.Security),
)

@Composable
fun ToolboxApp(
    accounts: List<OtpAccount>, otpStoreError: String?, qrScanned: String?,
    local: LocalSnapshot, busy: Boolean, storageError: String?, lastBackup: String?, importData: BackupData?, actions: LocalActions,
    onScanQr: () -> Unit, onScanOtp: () -> Unit, onImageQr: () -> Unit, onImageOtp: () -> Unit,
    onAddOtp: (OtpAccount) -> Unit, onDeleteOtp: (OtpAccount) -> Unit,
    onSaveQr: (Bitmap) -> Unit, onShareQr: (Bitmap) -> Unit, onCopy: (String, String) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("HOME") }
    fun open(id: String) { page = id; if (id in TOOL_IDS) actions.visit(id) }
    val mainPage = page in setOf("HOME", "ALL", "FAVORITES", "HISTORY", "PROFILE")
    BackHandler(page != "HOME") { page = "HOME" }
    MaterialTheme(colorScheme = lightColorScheme(primary = Indigo, background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(containerColor = Canvas, contentWindowInsets = WindowInsets.safeDrawing, bottomBar = {
            if (mainPage) NavigationBar(containerColor = Color.White) {
                listOf(Triple("HOME", "工具", Icons.Outlined.Home), Triple("FAVORITES", "收藏", Icons.Outlined.StarBorder),
                    Triple("HISTORY", "最近", Icons.Outlined.History), Triple("PROFILE", "我的", Icons.Outlined.PersonOutline)).forEach { (id, label, icon) ->
                    NavigationBarItem(selected = page == id || (id == "HOME" && page == "ALL"), onClick = { open(id) },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (storageError != null) Text(storageError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                if (page in TOOL_IDS) Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.toggleFavorite(page) }, enabled = !busy && storageError == null) {
                        Icon(if (page in local.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, null, Modifier.size(18.dp))
                        Text(if (page in local.favorites) " 已收藏" else " 收藏工具", fontSize = 12.sp)
                    }
                }
                val back = { page = "HOME" }
                when (page) {
                    "HOME", "ALL", "FAVORITES", "HISTORY" -> Directory(page, local, busy, actions, ::open)
                    "NOTES" -> NotesPage(local.notes, busy, actions, back)
                    "JSON", "TEXT", "UNITS", "PASSWORD", "TIME" -> UtilityPage(page, onCopy, back)
                    "QR" -> QrPage(qrScanned, onScanQr, onSaveQr, onShareQr, onCopy, onImageQr, back)
                    "CRYPTO" -> CryptoPage(onCopy, back)
                    "OTP" -> OtpPage(accounts, otpStoreError, onScanOtp, onAddOtp, onDeleteOtp, onCopy, onImageOtp, { page = "PROFILE" }, back)
                    "PROFILE" -> DataPage(local, accounts.size, busy, otpStoreError, lastBackup, actions, back)
                }
            }
        }
        if (importData != null) AlertDialog(onDismissRequest = { if (!busy) actions.dismissImport() },
            title = { Text("确认合并备份？") }, text = {
                Text("${importData.local.notes.size} 条便签、${importData.local.favorites.size} 个收藏、${importData.local.recent.size} 项最近使用、${importData.accounts.size} 个验证器账号。\n\n同 ID 便签保留修改时间较新的版本，时间相同保留本机；收藏与最近使用合并。现有记录不会批量删除。")
            }, confirmButton = { TextButton(onClick = actions.confirmImport, enabled = !busy) { Text("合并导入") } },
            dismissButton = { TextButton(onClick = actions.dismissImport, enabled = !busy) { Text("取消") } })
    }
}

@Composable
private fun Directory(page: String, local: LocalSnapshot, busy: Boolean, actions: LocalActions, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var clearHistory by remember { mutableStateOf(false) }
    val listed = when (page) {
        "FAVORITES" -> catalog.filter { it.id in local.favorites }
        "HISTORY" -> local.recent.mapNotNull { r -> catalog.find { it.id == r.tool } }
        else -> catalog
    }.filter { (category == "全部" || it.category == category) && (query.isBlank() || "${it.name} ${it.desc} ${it.id}".contains(query.trim(), true)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text(when (page) { "FAVORITES" -> "我的收藏"; "HISTORY" -> "最近使用"; "ALL" -> "全部工具"; else -> "每一天，轻装上阵" }, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (page == "HISTORY") "仅记录访问过的工具，不保存输入内容" else "${catalog.size} 款工具 · 无需登录 · 离线随手用", color = Muted, fontSize = 12.sp)
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("搜索工具、关键词") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
        if (page == "HOME" && query.isBlank()) item {
            Box(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(18.dp)).clickable { open("NOTES") }.padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("你的离线工作台", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("记录灵感、转换数据、保护账号", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                    Text("${local.notes.size} 条便签  ·  ${local.favorites.size} 个收藏  ›", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "效率", "开发", "安全").forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value) }) }
            }
        }
        item { SectionTitle(if (page == "HOME") "常用工具" else "${listed.size} 项工具", if (page == "HISTORY") "清空记录" else "全部工具 ›") {
            if (page == "HISTORY") clearHistory = true else open("ALL")
        } }
        if (listed.isEmpty()) item { EmptyState(if (page == "FAVORITES") "还没有收藏，点击工具旁的星标即可添加" else "暂无记录或没有匹配的工具") }
        items(listed.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tool ->
                    Card(onClick = { open(tool.id) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(13.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).background(Pale, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(tool.icon, null, tint = Indigo, modifier = Modifier.size(23.dp)) }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { actions.toggleFavorite(tool.id) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                    Icon(if (tool.id in local.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        "${if (tool.id in local.favorites) "取消收藏" else "收藏"}${tool.name}", tint = Indigo, modifier = Modifier.size(19.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp)); Text(tool.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(tool.desc, color = Muted, fontSize = 10.sp, minLines = 2)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("清空最近使用？") }, text = { Text("仅删除工具访问记录。") },
        confirmButton = { TextButton(onClick = { actions.clearRecent(); clearHistory = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("取消") } })
}

@Composable
internal fun EmptyState(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
    }
}
