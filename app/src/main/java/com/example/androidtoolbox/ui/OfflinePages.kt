package com.example.androidtoolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtoolbox.core.*
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun NotesPage(notes: List<LocalNote>, busy: Boolean, actions: LocalActions, onCopy: (String, String) -> Unit, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LocalNote?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun edit(note: LocalNote) { editingId = note.id; title = note.title; body = note.body; pinned = note.pinned }
    fun dismiss() {
        val original = notes.find { it.id == editingId }
        if (title != original?.title.orEmpty() || body != original?.body.orEmpty() || pinned != (original?.pinned ?: false)) discard = true
        else editingId = null
    }
    Column {
        Header("离线便签", "点击保存后写入本机 · 支持搜索与置顶", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索标题或正文") }, shape = RoundedCornerShape(14.dp)) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!pinnedOnly, { pinnedOnly = false }, label = { Text("全部 ${notes.size}") })
                FilterChip(pinnedOnly, { pinnedOnly = true }, label = { Text("置顶") })
                Spacer(Modifier.weight(1f)); TextButton(onClick = { edit(LocalNote(UUID.randomUUID().toString(), "", "")) }, enabled = !busy) { Text("＋ 新建") }
            } }
            val filtered = notes.filter { (!pinnedOnly || it.pinned) && (it.title.contains(query, true) || it.body.contains(query, true)) }
            if (filtered.isEmpty()) item { EmptyState(if (notes.isEmpty()) "还没有便签，点击「新建」记录第一个想法" else "没有匹配的便签") }
            items(filtered, key = { it.id }) { note ->
                Card(onClick = { edit(note) }, colors = CardDefaults.cardColors(containerColor = if (note.pinned) Pale else Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(note.title.ifBlank { "未命名便签" }, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp)); Text(note.body, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text((if (note.pinned) "置顶 · " else "") + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(note.updatedAt)), modifier = Modifier.weight(1f), color = Muted, fontSize = 10.sp)
                            NoteCopyButton(note.body, onCopy)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (editingId != null) AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("编辑便签", modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.heightIn(min = 48.dp)
                    .toggleable(value = pinned, role = Role.Checkbox, onValueChange = { pinned = it })
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(pinned, onCheckedChange = null)
                Text("置顶便签", fontSize = 13.sp, fontWeight = FontWeight.Normal)
            }
        }
    },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { if (it.length <= 200) title = it }, Modifier.fillMaxWidth(), label = { Text("标题（最多 200 字）") }, singleLine = true)
            OutlinedTextField(body, { if (it.length <= 100_000) body = it }, Modifier.fillMaxWidth().heightIn(max = 280.dp), label = { Text("正文") }, minLines = 5, maxLines = 10)
            NoteCopyButton(body, onCopy)
            if (notes.any { it.id == editingId }) TextButton(onClick = { deleting = notes.find { it.id == editingId } }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, null); Text("删除便签") }
        } },
        confirmButton = { TextButton(onClick = {
            val note = LocalNote(editingId!!, title.trim(), body, pinned)
            actions.saveNote(note) { editingId = null }
        }, enabled = !busy && (title.isNotBlank() || body.isNotBlank())) { Text("保存") } },
        dismissButton = { TextButton(onClick = { dismiss() }, enabled = !busy) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { discard = false; editingId = null }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条便签？") }, text = { Text("删除后可从此前导出的备份恢复。") },
        confirmButton = { TextButton(onClick = { deleting?.let { actions.deleteNote(it.id) }; deleting = null; editingId = null }, enabled = !busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
}

@Composable
private fun NoteCopyButton(body: String, onCopy: (String, String) -> Unit) {
    TextButton(onClick = { onCopy("便签正文", body) }, enabled = body.isNotEmpty()) {
        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("复制正文", fontSize = 12.sp)
    }
}

@Composable
internal fun UtilityPage(id: String, copy: (String, String) -> Unit, back: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(if (id == "JSON") "格式化" else "行去重") }
    var category by remember { mutableStateOf("长度") }
    var from by remember { mutableStateOf("米") }
    var to by remember { mutableStateOf("千米") }
    var length by remember { mutableStateOf("20") }
    var upper by remember { mutableStateOf(true) }; var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }; var symbols by remember { mutableStateOf(true) }
    var millis by remember { mutableStateOf(false) }; var reverse by remember { mutableStateOf(false) }; var utc by remember { mutableStateOf(false) }
    val zone = if (utc) ZoneId.of("UTC") else ZoneId.systemDefault()
    val title = when (id) { "JSON" -> "JSON 工具"; "TEXT" -> "文本处理"; "UNITS" -> "单位换算"; "PASSWORD" -> "密码生成"; else -> "时间戳" }
    fun process() {
        error = null; output = ""
        runCatching {
            when (id) {
                "JSON" -> JsonTools.format(input, mode == "压缩").let { if (mode == "校验") "✓ JSON 语法有效" else it }
                "TEXT" -> OfflineTools.text(input, mode)
                "UNITS" -> OfflineTools.convert(input, category, from, to)
                "PASSWORD" -> OfflineTools.password(length.toIntOrNull() ?: 0, upper, lower, digits, symbols)
                else -> if (reverse) OfflineTools.dateToTimestamp(input, millis, zone) else OfflineTools.timestampToDate(input, millis, zone)
            }
        }.onSuccess { output = it }.onFailure { error = it.message ?: "输入无效" }
    }
    Column {
        Header(title, "本机处理 · 输入与结果不会自动保存", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (id) {
                "JSON", "TEXT" -> item { ChoiceRow(if (id == "JSON") listOf("格式化", "压缩", "校验") else listOf("行去重", "移除空行", "行排序", "转大写", "转小写"), mode) { mode = it; output = ""; error = null } }
                "UNITS" -> {
                    item { ChoiceRow(OfflineTools.units.keys.toList(), category) { category = it; from = OfflineTools.units.getValue(it)[0]; to = OfflineTools.units.getValue(it)[1]; output = "" } }
                    item { Text("从", color = Ink); ChoiceRow(OfflineTools.units.getValue(category), from) { from = it; output = "" } }
                    item { Text("转换为", color = Ink); ChoiceRow(OfflineTools.units.getValue(category), to) { to = it; output = "" } }
                    item { Text("KB / MB / GB 使用 1000 进制；KiB / MiB / GiB 使用 1024 进制。", color = Muted, fontSize = 11.sp) }
                }
                "PASSWORD" -> {
                    item { OutlinedTextField(length, { if (it.length <= 3 && it.all(Char::isDigit)) { length = it; output = "" } }, Modifier.fillMaxWidth(), label = { Text("密码长度（8–128）") }, singleLine = true) }
                    item { Column {
                        Toggle("大写字母 A–Z", upper) { upper = it; output = "" }; Toggle("小写字母 a–z", lower) { lower = it; output = "" }
                        Toggle("数字 0–9", digits) { digits = it; output = "" }; Toggle("符号", symbols) { symbols = it; output = "" }
                    } }
                    item { Text("每次使用安全随机源生成，至少包含每一类选中的字符。请保存到你信任的密码管理器。", fontSize = 12.sp, color = Muted) }
                }
                "TIME" -> {
                    item { ChoiceRow(listOf("时间戳 → 日期", "日期 → 时间戳"), if (reverse) "日期 → 时间戳" else "时间戳 → 日期") { reverse = it.startsWith("日期"); input = ""; output = "" } }
                    item { ChoiceRow(listOf("秒", "毫秒"), if (millis) "毫秒" else "秒") { millis = it == "毫秒"; output = "" } }
                    item { Toggle("使用 UTC（关闭为设备时区）", utc) { utc = it; output = "" }; Text("当前时区：$zone", color = Muted, fontSize = 12.sp) }
                    item { TextButton(onClick = {
                        input = if (reverse) DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(zone).format(Instant.now()) else (if (millis) System.currentTimeMillis() else Instant.now().epochSecond).toString()
                        output = ""
                    }) { Text("填入当前时间") } }
                }
            }
            if (id != "PASSWORD") item {
                OutlinedTextField(input, { if (it.length <= 256_000) { input = it; output = ""; error = null } }, Modifier.fillMaxWidth(),
                    minLines = if (id in setOf("JSON", "TEXT")) 5 else 1, maxLines = 10, shape = RoundedCornerShape(14.dp),
                    label = { Text(if (id == "TIME" && reverse) "yyyy-MM-dd HH:mm:ss" else "输入内容") })
                if (id == "TEXT") Text("${input.codePointCount(0, input.length)} 个字符 · ${if (input.isEmpty()) 0 else input.lines().size} 行", color = Muted, fontSize = 11.sp)
            }
            item { PrimaryButton(if (id == "PASSWORD") "生成密码" else "开始处理", ::process) }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (output.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF2FF)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("处理结果", color = Indigo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        SelectionContainer { Text(output, fontFamily = FontFamily.Monospace, color = Ink, fontSize = 13.sp, modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) }
                        TextButton(onClick = { copy(if (id == "PASSWORD") "密码" else "处理结果", output) }) { Text("复制结果") }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
internal fun ChoiceRow(options: List<String>, selected: String, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { value -> FilterChip(selected == value, { choose(value) }, label = { Text(value) }) }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, change); Text(label, fontSize = 13.sp, color = Ink) }
}

@Composable
internal fun DataPage(local: LocalSnapshot, accountCount: Int, busy: Boolean, otpError: String?, lastBackup: String?, actions: LocalActions, back: () -> Unit) {
    var encrypted by rememberSaveable { mutableStateOf(true) }
    var includeOtp by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    val canExport = !busy && (!encrypted || (password.isNotBlank() && password.length >= 12 && password == repeatPassword))
    Column {
        Header("数据与备份", "工具箱 0.2.0 · 数据在本地，迁移由你决定", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Card(colors = CardDefaults.cardColors(containerColor = Pale)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地数据已就绪", color = Ink, fontWeight = FontWeight.Bold)
                    Text("${local.notes.size} 条便签 · ${local.favorites.size} 个收藏 · ${local.recent.size} 项最近使用", color = Muted, fontSize = 11.sp)
                    Text("最近导出到文件：${lastBackup ?: "尚未导出"}", color = Muted, fontSize = 11.sp)
                }
            } }
            item { SectionTitle("导出与迁移") }
            item { ChoiceRow(listOf("口令加密备份", "JSON 普通备份"), if (encrypted) "口令加密备份" else "JSON 普通备份") { encrypted = it.startsWith("口令"); if (!encrypted) includeOtp = false } }
            if (encrypted) {
                item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("备份口令（至少 12 个字符）") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
                item { OutlinedTextField(repeatPassword, { repeatPassword = it }, Modifier.fillMaxWidth(), label = { Text("再次输入口令") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeOtp, { includeOtp = it }, enabled = otpError == null && !busy); Text("包含 $accountCount 个验证器账号", fontSize = 13.sp) }
                    if (otpError != null) Text(otpError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    Text("请记住口令，忘记后无法找回。接收设备使用口令解密，不依赖旧手机密钥。", color = Muted, fontSize = 11.sp)
                }
            } else item { Text("普通 JSON 包含便签、收藏和最近使用，正文可直接阅读；不含验证器账号。", color = Muted, fontSize = 12.sp) }
            item { PrimaryButton("导出备份文件", { actions.export(if (encrypted) password else null, includeOtp && encrypted, false) }, canExport) }
            if (encrypted) item { OutlinedButton(onClick = { actions.export(password, includeOtp, true) }, enabled = canExport, modifier = Modifier.fillMaxWidth()) { Text("分享加密备份 · 邮箱 / 其他应用") } }
            item { HorizontalDivider(); SectionTitle("从备份恢复") }
            item { OutlinedButton(onClick = actions.selectImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("1. 选择 JSON 备份文件") } }
            item { OutlinedTextField(importPassword, { importPassword = it }, Modifier.fillMaxWidth(), label = { Text("原备份口令（普通 JSON 留空）") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
            item { PrimaryButton("2. 解析并预览备份", { actions.previewImport(importPassword) }, !busy) }
            item { Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("关于本地数据", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("SQLite 保存在应用私有目录，验证器密钥额外使用 Android Keystore 加密。便签为普通本地数据，不适合保存密码。卸载应用或清除应用数据会删除本地记录，请先将备份导出到应用外。", color = Muted, fontSize = 11.sp)
                    Text("文件分享由系统应用处理；工具箱不会自动发送邮件或后台同步。相机扫码与图片解析均在本机完成，无需 Google Play 服务或联网。", color = Muted, fontSize = 11.sp)
                }
            } }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
