package com.example.androidtoolbox.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

@Composable
internal fun SettingsPage(back: () -> Unit, onAbout: () -> Unit, onFiles: () -> Unit) {
    Column {
        Header("设置", "应用信息与本地文件", back)
        Column(Modifier.padding(horizontal = 22.dp)) {
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("应用简介与版本") },
                leadingContent = { Icon(Icons.Outlined.Info, null, tint = Indigo) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onAbout),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("文件管理") },
                supportingContent = { Text("浏览应用私有数据目录") },
                leadingContent = { Icon(Icons.Outlined.Folder, null, tint = Indigo) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onFiles),
            )
        }
    }
}

@Composable
internal fun AboutPage(back: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    Column {
        Header("关于", "应用信息", back)
        Card(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工具箱", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                version?.let { Text("版本 $it", color = Muted) }
                Text("一款离线工具箱，提供便签、文本与图片处理、二维码和身份验证器等工具。", color = Ink)
            }
        }
    }
}

@Composable
internal fun PrivateFilesPage(relativePath: String, back: () -> Unit, onOpenDirectory: (String) -> Unit) {
    val context = LocalContext.current
    val root = context.dataDir
    var refresh by remember { mutableIntStateOf(0) }
    val listing by produceState<Result<List<PrivateEntry>>?>(null, root, relativePath, refresh) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { listPrivateDirectory(root, relativePath) } }
    }
    Column {
        Header("文件管理", "应用私有数据目录 · 只读", back, trailing = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新目录", tint = Ink) }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            item {
                SelectionContainer(Modifier.padding(bottom = 12.dp)) {
                    Text(File(root, relativePath).absolutePath, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            val result = listing
            val entries = result?.getOrNull()
            when {
                result == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                entries == null -> item { EmptyState("无法读取此目录，请刷新或返回上一级。") }
                entries.isEmpty() -> item { EmptyState("此目录为空") }
                else -> items(entries, key = { it.name }) { entry ->
                    val size = entry.size
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(if (size == null) "文件夹" else Formatter.formatFileSize(context, size)) },
                        leadingContent = {
                            Icon(if (size == null) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = Indigo)
                        },
                        trailingContent = if (size == null) {
                            { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }
                        } else null,
                        modifier = if (size == null) {
                            Modifier.clickable(role = Role.Button) { onOpenDirectory(entry.name) }
                        } else Modifier,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private data class PrivateEntry(val name: String, val size: Long?)

private fun listPrivateDirectory(dataDir: File, relativePath: String): List<PrivateEntry> {
    val root = dataDir.canonicalFile
    val directory = File(root, relativePath)
    // Only browse real directories under the app root, even if an entry changes after listing.
    require(directory.toPath().startsWith(root.toPath()) && directory.canonicalFile == directory)
    val files = directory.listFiles() ?: throw IOException("Directory listing unavailable")
    return files.mapNotNull { file ->
        when {
            Files.isSymbolicLink(file.toPath()) -> null
            file.isDirectory -> PrivateEntry(file.name, null)
            file.isFile -> PrivateEntry(file.name, file.length())
            else -> null
        }
    }.sortedWith(compareBy<PrivateEntry> { it.size != null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
