package com.example.androidtoolbox.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import androidx.core.net.toUri
import com.example.androidtoolbox.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ImageGroup(val label: String) { EDIT("编辑"), EXPORT("导出"), INSPECT("查看") }

internal enum class ImageOperation(val label: String, val group: ImageGroup, val action: String = "生成结果") {
    CROP("裁剪", ImageGroup.EDIT, "裁剪图片"),
    ROTATE("旋转 / 翻转", ImageGroup.EDIT),
    RESIZE("尺寸调整", ImageGroup.EDIT, "调整尺寸"),
    WATERMARK("文字水印", ImageGroup.EDIT, "添加水印"),
    STITCH("图片拼接", ImageGroup.EDIT, "拼接图片"),
    COMPRESS("压缩", ImageGroup.EXPORT, "压缩图片"),
    CONVERT("格式转换", ImageGroup.EXPORT, "转换格式"),
    BASE64("Base64", ImageGroup.EXPORT, "生成 Base64"),
    QR("图片二维码", ImageGroup.EXPORT, "生成二维码"),
    COLOR("颜色提取", ImageGroup.INSPECT),
    EXIF("EXIF", ImageGroup.INSPECT),
    INFO("像素 / DPI", ImageGroup.INSPECT),
}

private data class ImagePreview(val source: ImageSource, val bitmap: Bitmap)
private data class LoadedImages(val sources: List<ImageSource>, val preview: ImagePreview, val palette: List<Int>)
private val selectedImagesSaver = listSaver<List<String>, String>(save = { it }, restore = { it.toList() })

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageToolsPage(copy: (String, String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val cache = remember(context) { File(context.cacheDir, "image-tools") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var operation by rememberSaveable { mutableStateOf(ImageOperation.CROP) }
    var single by rememberSaveable { mutableStateOf<String?>(null) }
    var multiple by rememberSaveable(stateSaver = selectedImagesSaver) { mutableStateOf(emptyList()) }
    var resultName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val stitch = operation == ImageOperation.STITCH
    val selected = if (stitch) multiple else listOfNotNull(single)

    fun setOperation(next: ImageOperation) {
        if (next == operation) return
        operation = next
        resultName = null
        notice = null
        error = null
    }

    fun work(action: suspend () -> Unit) {
        if (working) return
        working = true
        notice = null
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { error = "内存不足，请先缩小图片后再处理" }
            catch (failure: Exception) { error = failure.message ?: "处理失败，请重试" }
            finally { working = false }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            single = uri.toString()
            resultName = null
            error = null
            notice = null
        }
    }
    val openMultiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size in 2..ImageTools.MAX_STITCH_IMAGES) {
            multiple = uris.map(Uri::toString)
            resultName = null
            error = null
            notice = null
        } else if (uris.isNotEmpty()) {
            error = "请一次选择 2–8 张图片"
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val name = pendingSave
        pendingSave = null
        val uri = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && uri != null && name != null) work {
            withContext(Dispatchers.IO) {
                val file = File(cache, name)
                check(file.isFile) { "临时结果已失效，请重新生成" }
                val output = resolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            }
            notice = "已保存文件"
        }
    }

    LaunchedEffect(cache) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            cache.listFiles()?.filter { it.name.startsWith("toolbox-image-") && it.lastModified() < cutoff }?.forEach(File::delete)
        }
    }
    // Key the load so the previous selection is never actionable while a new one is loading.
    val load = key(selected) {
        produceState<Result<LoadedImages>?>(null) {
            if (selected.isEmpty()) return@produceState
            value = imageLoad {
                val sources = selected.map { resolver.readImageSource(it.toUri()) }
                val source = sources.first()
                val bitmap = ImageTools.preview(resolver, source)
                LoadedImages(sources, ImagePreview(source, bitmap), ImageTools.palette(bitmap))
            }
        }.value
    }
    val images = load?.getOrNull()
    val source = images?.preview?.source
    var crop by remember(source?.uri) { mutableStateOf(source?.let(CropFields::full)) }
    var pickedColor by remember(source?.uri) { mutableStateOf<Int?>(null) }

    fun run(format: ImageFormat?, encode: (File) -> Unit) = work {
        resultName = null
        resultName = withContext(Dispatchers.IO) {
            check(cache.isDirectory || cache.mkdirs()) { "无法创建临时目录" }
            val file = File.createTempFile("toolbox-image-", ".${format?.extension ?: "txt"}", cache)
            var complete = false
            try {
                encode(file)
                complete = true
                file.name
            } finally {
                if (!complete) file.delete()
            }
        }
    }

    LaunchedEffect(resultName) {
        if (resultName != null) listState.animateScrollToItem(4)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header("图片处理工具", "本地处理 · 原图保留", back) }
        item {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageGroup.entries.forEach { group ->
                        FilterChip(group == operation.group, { setOperation(ImageOperation.entries.first { it.group == group }) },
                            enabled = !working, label = { Text(group.label) }, modifier = Modifier.weight(1f))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageOperation.entries.filter { it.group == operation.group }.forEach { tool ->
                        FilterChip(tool == operation, { setOperation(tool) }, enabled = !working, label = { Text(tool.label) })
                    }
                }
            }
        }
        item {
            ImagePanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (stitch) "原图列表" else "原图", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = {
                        if (stitch) openMultiple.launch(arrayOf("image/*")) else open.launch(arrayOf("image/*"))
                    }) {
                        Text(if (stitch) "选择多张" else if (selected.isEmpty()) "选择图片" else "更换图片")
                    }
                }
                when {
                    images != null -> {
                        val selection = crop.takeIf { operation == ImageOperation.CROP }
                        SourcePreview(images.preview, selection, if (!working && selection != null) {
                            { fields -> crop = fields }
                        } else null, if (operation == ImageOperation.COLOR) ({ color -> pickedColor = color }) else null)
                        val first = images.preview.source
                        val size = first.bytes?.let { " · " + Formatter.formatFileSize(context, it) }.orEmpty()
                        ImageDetail("${first.name} · ${first.width} × ${first.height} px$size")
                        if (stitch) {
                            images.sources.forEachIndexed { index, image ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${image.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (index > 0) TextButton(enabled = !working, onClick = {
                                        multiple = multiple.toMutableList().apply { add(index - 1, removeAt(index)) }.toList()
                                        resultName = null
                                        error = null
                                        notice = null
                                    }) { Text("上移") }
                                }
                            }
                        }
                    }
                    selected.isEmpty() -> ImageDetail(if (stitch) "请选择 2–8 张图片" else "从系统文件选择图片")
                    load == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Text(load.exceptionOrNull()?.message ?: "无法读取图片", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            ImagePanel {
                if (operation.group == ImageGroup.INSPECT) {
                    InspectImage(operation, images, pickedColor, copy)
                } else {
                    key(operation, source?.uri) {
                        ImageOperationControls(operation, images?.sources.orEmpty(), crop, { crop = it }, working, ::run)
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        resultName?.let { name ->
            item {
                key(name) {
                    ImageResultPanel(File(cache, name), source.takeIf { operation == ImageOperation.COMPRESS }, working,
                        save = { mime ->
                            pendingSave = name
                            save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                type = mime
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_TITLE, "toolbox-image-${System.currentTimeMillis()}.${File(name).extension}")
                            })
                        },
                        reuse = {
                            single = Uri.fromFile(File(cache, name)).toString()
                            setOperation(ImageOperation.CROP)
                            resultName = null
                            error = null
                            notice = null
                        },
                        copyText = { file -> work {
                            val value = withContext(Dispatchers.IO) { file.readText() }
                            copy("图片 Base64", value)
                        } },
                    )
                }
            }
        }
        notice?.let { message -> item { Text(message, color = Indigo, modifier = Modifier.padding(horizontal = 22.dp)) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private suspend fun <T> imageLoad(block: () -> T): Result<T> {
    return try { Result.success(withContext(Dispatchers.IO) { block() }) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: OutOfMemoryError) { Result.failure(IllegalArgumentException("图片过大，无法读取预览")) }
    catch (failure: Exception) { Result.failure(failure) }
}

@Composable
private fun ImageResultPanel(
    file: File,
    original: ImageSource?,
    working: Boolean,
    save: (String) -> Unit,
    reuse: () -> Unit,
    copyText: (File) -> Unit,
) {
    val context = LocalContext.current
    val text = file.extension == "txt"
    val result by produceState<Result<Any>?>(null, file) {
        value = imageLoad {
            check(file.isFile) { "临时结果已失效，请重新生成" }
            if (text) {
                val prefix = ByteArray(minOf(144L, file.length()).toInt())
                DataInputStream(file.inputStream()).use { it.readFully(prefix) }
                String(prefix, Charsets.US_ASCII)
            }
            else {
                val source = context.contentResolver.readImageSource(Uri.fromFile(file))
                ImagePreview(source, ImageTools.preview(context.contentResolver, source))
            }
        }
    }
    ImagePanel {
        Text("处理结果", fontWeight = FontWeight.Bold)
        when (val loaded = result?.getOrNull()) {
            is ImagePreview -> {
                SourcePreview(loaded)
                val source = loaded.source
                val size = Formatter.formatFileSize(context, source.bytes ?: 0)
                val previous = original?.bytes
                val change = if (previous != null) "${Formatter.formatFileSize(context, previous)} → $size" else size
                val format = ImageFormat.entries.first { it.extension == file.extension }
                ImageDetail("${source.width} × ${source.height} px · ${format.label} · $change")
                if (previous != null && (source.bytes ?: 0) >= previous) {
                    ImageDetail("此设置下文件未变小，可降低质量或先缩小尺寸。")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({ save(format.mime) }, enabled = !working) { Text("保存文件") }
                    TextButton(reuse, enabled = !working) { Text("使用结果继续处理") }
                }
            }
            is String -> {
                val length = file.length()
                ImageDetail("${Formatter.formatFileSize(context, length)} · TXT")
                Text(if (length > loaded.length) "$loaded…" else loaded, color = Ink, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({ save("text/plain") }, enabled = !working) { Text("保存文本") }
                    if (length <= 256 * 1024) TextButton({ copyText(file) }, enabled = !working) { Text("复制 Base64") }
                    else ImageDetail("长文本请保存为文件")
                }
            }
            else -> if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(result?.exceptionOrNull()?.message ?: "无法读取处理结果", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun InspectImage(operation: ImageOperation, images: LoadedImages?, pickedColor: Int?, copy: (String, String) -> Unit) {
    Text(operation.label, fontWeight = FontWeight.Bold)
    val source = images?.preview?.source
    if (source == null) {
        ImageDetail("选择图片后查看")
        return
    }
    when (operation) {
        ImageOperation.EXIF -> {
            if (source.exif.isEmpty()) ImageDetail("未读到拍摄 EXIF 信息")
            source.exif.forEach { (name, value) -> ImageDetail("$name  $value") }
            if (source.exif.isNotEmpty()) TextButton({ copy("EXIF", source.exif.entries.joinToString("\n") { "${it.key}  ${it.value}" }) }) { Text("复制 EXIF") }
        }
        ImageOperation.INFO -> {
            ImageDetail("原始像素  ${source.rawWidth} × ${source.rawHeight} px")
            if (source.swapped) ImageDetail("按方向显示  ${source.width} × ${source.height} px")
            val dpi = source.dpi
            ImageDetail(if (dpi == null) "文件 DPI  未记录" else String.format(Locale.ROOT, "文件 DPI  %.2f × %.2f", dpi.first, dpi.second))
            ImageDetail("文件类型  ${source.mime}")
        }
        ImageOperation.COLOR -> {
            ImageDetail("轻点原图取色，或点击下方色块复制 HEX 值。")
            pickedColor?.let { ColorButton(it, "取色", copy) }
            if (images.palette.isEmpty()) ImageDetail("未提取到可见颜色")
            images.palette.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { ColorButton(it, "", copy) } }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ColorButton(pixel: Int, label: String, copy: (String, String) -> Unit) {
    val hex = String.format(Locale.ROOT, "#%06X", pixel and 0xffffff)
    OutlinedButton({ copy("图片颜色", hex) }, contentPadding = PaddingValues(horizontal = 9.dp)) {
        Box(Modifier.size(14.dp).background(Color(pixel or (0xff shl 24)), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(6.dp))
        Text(if (label.isEmpty()) hex else "$label $hex")
    }
}

internal data class CropFields(val x: String, val y: String, val width: String, val height: String) {
    fun rectangle(): android.graphics.Rect? {
        val x = x.toIntOrNull() ?: return null
        val y = y.toIntOrNull() ?: return null
        val w = width.toIntOrNull() ?: return null
        val h = height.toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || x.toLong() + w > Int.MAX_VALUE || y.toLong() + h > Int.MAX_VALUE) return null
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    companion object {
        fun full(source: ImageSource) = CropFields("0", "0", source.width.toString(), source.height.toString())
    }
}

@Composable
private fun SourcePreview(
    preview: ImagePreview,
    crop: CropFields? = null,
    selectCrop: ((CropFields) -> Unit)? = null,
    pickColor: ((Int) -> Unit)? = null,
) {
    val (source, bitmap) = preview
    val bitmapImage = remember(bitmap) { bitmap.asImageBitmap() }
    val onCrop by rememberUpdatedState(selectCrop)
    val onColor by rememberUpdatedState(pickColor)
    val gestures = when {
        selectCrop != null -> Modifier.pointerInput(preview) {
            val area = fittedImage(size.width.toFloat(), size.height.toFloat(), bitmap)
            var start: Offset? = null
            var end = Offset.Zero
            detectDragGestures(onDragStart = {
                start = it.takeIf(area::contains)
                end = it
            }, onDragEnd = { start = null }, onDragCancel = { start = null }) { change, delta ->
                start?.let { origin ->
                    change.consume()
                    end += delta
                    fun x(pos: Float) = ((pos - area.left) / area.width * source.width).roundToInt().coerceIn(0, source.width)
                    fun y(pos: Float) = ((pos - area.top) / area.height * source.height).roundToInt().coerceIn(0, source.height)
                    val left = minOf(x(origin.x), x(end.x)).coerceAtMost(source.width - 1)
                    val top = minOf(y(origin.y), y(end.y)).coerceAtMost(source.height - 1)
                    val width = (maxOf(x(origin.x), x(end.x)) - left).coerceAtLeast(1)
                    val height = (maxOf(y(origin.y), y(end.y)) - top).coerceAtLeast(1)
                    onCrop?.invoke(CropFields(left.toString(), top.toString(), width.toString(), height.toString()))
                }
            }
        }
        pickColor != null -> Modifier.pointerInput(preview) {
            val area = fittedImage(size.width.toFloat(), size.height.toFloat(), bitmap)
            detectTapGestures { point ->
                if (area.contains(point)) {
                    val x = ((point.x - area.left) * bitmap.width / area.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y = ((point.y - area.top) * bitmap.height / area.height).toInt().coerceIn(0, bitmap.height - 1)
                    onColor?.invoke(bitmap[x, y])
                }
            }
        }
        else -> Modifier
    }
    Image(bitmapImage, if (crop == null) "图片预览" else "原图与裁剪框",
        Modifier.fillMaxWidth().height(212.dp).clip(RoundedCornerShape(10.dp)).background(Pale)
            .then(gestures).drawWithContent {
                drawContent()
                val bounds = crop?.rectangle() ?: return@drawWithContent
                if (bounds.left < 0 || bounds.top < 0 || bounds.right > source.width || bounds.bottom > source.height || bounds.isEmpty) return@drawWithContent
                val area = fittedImage(size.width, size.height, bitmap)
                val left = area.left + area.width * bounds.left / source.width
                val top = area.top + area.height * bounds.top / source.height
                val right = area.left + area.width * bounds.right / source.width
                val bottom = area.top + area.height * bounds.bottom / source.height
                val shade = Color.Black.copy(alpha = .46f)
                drawRect(shade, area.topLeft, Size(area.width, top - area.top))
                drawRect(shade, Offset(area.left, bottom), Size(area.width, area.bottom - bottom))
                drawRect(shade, Offset(area.left, top), Size(left - area.left, bottom - top))
                drawRect(shade, Offset(right, top), Size(area.right - right, bottom - top))
                drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
                for (step in 1..2) {
                    val x = left + (right - left) * step / 3
                    val y = top + (bottom - top) * step / 3
                    val guide = Color.White.copy(alpha = .65f)
                    drawLine(guide, Offset(x, top), Offset(x, bottom))
                    drawLine(guide, Offset(left, y), Offset(right, y))
                }
            }, contentScale = ContentScale.Fit)
}

private fun fittedImage(width: Float, height: Float, bitmap: Bitmap): Rect {
    val scale = minOf(width / bitmap.width, height / bitmap.height)
    val w = scale * bitmap.width
    val h = scale * bitmap.height
    return Rect(Offset((width - w) / 2, (height - h) / 2), Size(w, h))
}

@Composable
private fun ImagePanel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 22.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Ink)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ImageDetail(text: String) = Text(text, color = Muted, fontSize = 13.sp)
