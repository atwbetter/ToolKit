package com.example.androidtoolbox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.androidtoolbox.core.BorderPreset
import com.example.androidtoolbox.core.BorderStyle
import com.example.androidtoolbox.core.ImageBorder
import com.example.androidtoolbox.core.ImageFormat
import com.example.androidtoolbox.core.ImageSource
import com.example.androidtoolbox.core.ImageTools
import com.example.androidtoolbox.core.WatermarkColor
import com.example.androidtoolbox.core.WatermarkMode
import com.example.androidtoolbox.core.WatermarkPosition
import com.example.androidtoolbox.core.WatermarkStyle
import com.example.androidtoolbox.core.photoExifLines
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageOperationControls(
    operation: ImageOperation,
    sources: List<ImageSource>,
    crop: CropFields?,
    updateCrop: (CropFields) -> Unit,
    watermark: WatermarkStyle,
    updateWatermark: (WatermarkStyle) -> Unit,
    showWatermarkPreview: () -> Unit,
    border: BorderStyle,
    updateBorder: (BorderStyle) -> Unit,
    working: Boolean,
    run: (ImageFormat?, (File) -> Unit) -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val source = sources.firstOrNull()
    val enabled = !working
    fun image(format: ImageFormat = ImageFormat.PNG, quality: Int = 100, create: () -> Bitmap) {
        run(format) { file ->
            val bitmap = create()
            try { file.outputStream().use { ImageTools.write(bitmap, format, quality, it) } }
            finally { bitmap.recycle() }
        }
    }
    fun edit(transform: (Bitmap) -> Bitmap) {
        image { ImageTools.edit(resolver, checkNotNull(source), transform) }
    }

    Text(operation.label, fontWeight = FontWeight.Bold)
    if (source == null && operation != ImageOperation.QR) {
        ImageDetail(if (operation == ImageOperation.STITCH) "选择图片后设置拼接方向" else "选择图片后开始处理")
        return
    }
    when (operation) {
        ImageOperation.CROP -> {
            val fields = crop ?: return
            ImageDetail("在原图上拖出裁剪框，或输入像素坐标。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField("左上 X", fields.x, enabled, Modifier.weight(1f)) { updateCrop(fields.copy(x = it)) }
                PixelField("左上 Y", fields.y, enabled, Modifier.weight(1f)) { updateCrop(fields.copy(y = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField("宽度", fields.width, enabled, Modifier.weight(1f)) { updateCrop(fields.copy(width = it)) }
                PixelField("高度", fields.height, enabled, Modifier.weight(1f)) { updateCrop(fields.copy(height = it)) }
            }
            TextButton({ updateCrop(CropFields.full(checkNotNull(source))) }, enabled = enabled) { Text("重置裁剪框") }
            val bounds = fields.rectangle()
            PrimaryButton(operation.action, {
                checkNotNull(bounds)
                edit { ImageTools.crop(it, bounds.left, bounds.top, bounds.width(), bounds.height()) }
            }, enabled = enabled && bounds != null && !bounds.isEmpty)
        }
        ImageOperation.ROTATE -> {
            var degrees by rememberSaveable { mutableIntStateOf(0) }
            var horizontal by rememberSaveable { mutableStateOf(false) }
            var vertical by rememberSaveable { mutableStateOf(false) }
            Choices(listOf(0, 90, 180, 270), degrees, enabled, { if (it == 0) "原角度" else "$it°" }) { degrees = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(horizontal, { horizontal = !horizontal }, enabled = enabled, label = { Text("水平翻转") })
                FilterChip(vertical, { vertical = !vertical }, enabled = enabled, label = { Text("垂直翻转") })
            }
            ImageDetail("按原图方向先翻转，再顺时针旋转。")
            PrimaryButton(operation.action, { edit { ImageTools.orient(it, degrees, horizontal, vertical) } }, enabled)
        }
        ImageOperation.RESIZE -> {
            val original = checkNotNull(source)
            var width by rememberSaveable { mutableStateOf(original.width.toString()) }
            var height by rememberSaveable { mutableStateOf(original.height.toString()) }
            var keepRatio by rememberSaveable { mutableStateOf(true) }
            fun matched(value: String, from: Int, to: Int) = value.toIntOrNull()?.let {
                (it.toDouble() * to / from).roundToLong().coerceAtLeast(1).toString()
            }.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField("宽度（px）", width, enabled, Modifier.weight(1f)) {
                    width = it
                    if (keepRatio) height = matched(it, original.width, original.height)
                }
                PixelField("高度（px）", height, enabled, Modifier.weight(1f)) {
                    height = it
                    if (keepRatio) width = matched(it, original.height, original.width)
                }
            }
            FilterChip(keepRatio, {
                keepRatio = !keepRatio
                if (keepRatio) height = matched(width, original.width, original.height)
            }, enabled = enabled, label = { Text("保持比例") })
            ImageDetail("每边最多 8192 px，总像素最多 1600 万。")
            val targetWidth = width.toIntOrNull()
            val targetHeight = height.toIntOrNull()
            PrimaryButton(operation.action, {
                image { ImageTools.resize(resolver, original, checkNotNull(targetWidth), checkNotNull(targetHeight)) }
            }, enabled = enabled && targetWidth != null && targetHeight != null)
        }
        ImageOperation.WATERMARK -> {
            WatermarkControls(watermark, updateWatermark, enabled)
            val ready = enabled && watermark.text.isNotBlank()
            TextButton(showWatermarkPreview, enabled = ready) { Text("查看整图预览") }
            PrimaryButton(operation.action, { edit { ImageTools.watermark(it, watermark) } }, enabled = ready)
        }
        ImageOperation.BORDER -> {
            val original = checkNotNull(source)
            val layout = ImageBorder.layout(original.width, original.height, border, original.exif)
            BorderControls(border, updateBorder, enabled)
            if (border.preset == BorderPreset.EXIF) {
                val exifLines = photoExifLines(original.exif)
                ImageDetail(if (exifLines.isEmpty()) "未读取到拍摄参数，可以添加说明文字。" else exifLines.joinToString("\n"))
            }
            ImageDetail("输出尺寸 ${layout.width} × ${layout.height} px · PNG")
            if (!layout.canExport) {
                Text("加框后尺寸超出限制，请减小边框或先缩小图片。", color = MaterialTheme.colorScheme.error)
            }
            PrimaryButton(operation.action, { edit { ImageBorder.add(it, border, original.exif) } },
                enabled && layout.canExport && border.argb != null)
        }
        ImageOperation.STITCH -> {
            var vertical by rememberSaveable { mutableStateOf(true) }
            Choices(listOf(true, false), vertical, enabled, { if (it) "纵向" else "横向" }) { vertical = it }
            ImageDetail("按列表顺序拼接。图片等比缩放至相同${if (vertical) "宽度" else "高度"}。")
            PrimaryButton(operation.action, {
                val direction = vertical
                image { ImageTools.stitch(resolver, sources, direction) }
            }, enabled = enabled && sources.size >= 2)
        }
        ImageOperation.COMPRESS, ImageOperation.CONVERT -> {
            val compress = operation == ImageOperation.COMPRESS
            val original = checkNotNull(source)
            val formats = if (compress) listOf(ImageFormat.JPEG, ImageFormat.WEBP) else ImageFormat.entries
            var format by rememberSaveable { mutableStateOf(if (compress) ImageFormat.JPEG else ImageFormat.PNG) }
            var quality by rememberSaveable { mutableIntStateOf(75) }
            Choices(formats, format, enabled, ImageFormat::label) { format = it }
            if (compress) {
                ImageDetail("画质 $quality%")
                Slider(quality.toFloat(), { quality = it.roundToInt() }, enabled = enabled, valueRange = 1f..100f)
            }
            if (format == ImageFormat.JPEG) ImageDetail("JPEG 会将透明背景填为白色。")
            ImageDetail("导出会重新编码图片。超大图请先调整尺寸。")
            PrimaryButton(operation.action, {
                image(format, if (compress) quality else 90) { ImageTools.edit(resolver, original) { it } }
            }, enabled)
        }
        ImageOperation.BASE64 -> {
            var dataUrl by rememberSaveable { mutableStateOf(true) }
            FilterChip(dataUrl, { dataUrl = !dataUrl }, enabled = enabled, label = { Text("添加 Data URL 前缀") })
            ImageDetail("编码原始文件字节。最大 32 MiB，结果可保存为文本。")
            PrimaryButton(operation.action, {
                val includePrefix = dataUrl
                run(null) { ImageTools.writeBase64(resolver, checkNotNull(source), includePrefix, it) }
            }, enabled)
        }
        ImageOperation.QR -> {
            var embed by rememberSaveable { mutableStateOf(false) }
            var url by rememberSaveable { mutableStateOf("") }
            Choices(listOf(false, true), embed, enabled, { if (it) "嵌入小图片" else "图片网址" }) { embed = it }
            if (embed) {
                ImageDetail("把最多 2000 字节的原图嵌入二维码。扫码器需支持读取 Data URL。")
            } else {
                OutlinedTextField(url, { url = it.take(3000) }, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("图片网址") }, placeholder = { Text("https://…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                ImageDetail("二维码记录你提供的网址。应用无需上传图片。")
            }
            PrimaryButton(operation.action, {
                val useImage = embed
                val address = url
                image { if (useImage) ImageTools.imageQr(resolver, checkNotNull(source)) else ImageTools.urlQr(address) }
            }, enabled = enabled && (if (embed) source != null else url.isNotBlank()))
        }
        else -> Unit
    }
}

@Composable
private fun WatermarkControls(style: WatermarkStyle, update: (WatermarkStyle) -> Unit, enabled: Boolean) {
    OutlinedTextField(style.text, { update(style.copy(text = it.take(WatermarkStyle.MAX_TEXT))) },
        Modifier.fillMaxWidth(), enabled = enabled, label = { Text("水印文字") },
        placeholder = { Text("例如：仅供本人使用") }, singleLine = true)
    Choices(WatermarkMode.entries, style.mode, enabled, WatermarkMode::label) { update(style.copy(mode = it)) }

    if (style.mode == WatermarkMode.SINGLE) {
        ImageDetail("放置位置")
        WatermarkPosition.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { position ->
                    FilterChip(style.position == position, { update(style.copy(position = position)) },
                        modifier = Modifier.weight(1f), enabled = enabled, label = { Text(position.label) })
                }
            }
        }
    } else {
        ImageDetail("水印错行排列，覆盖整张图片。")
        ImageSlider("平铺间距", style.spacing, WatermarkStyle.SPACINGS, "%", enabled) {
            update(style.copy(spacing = it))
        }
    }

    ImageSlider("倾斜角度", style.angle, WatermarkStyle.ANGLES, "°", enabled) { update(style.copy(angle = it)) }
    Choices(listOf(-30, 0, 30), style.angle, enabled, { if (it > 0) "+$it°" else "$it°" }) { update(style.copy(angle = it)) }
    ImageDetail("文字颜色")
    Choices(WatermarkColor.entries, style.color, enabled, WatermarkColor::label) { update(style.copy(color = it)) }
    ImageSlider("字号", style.size, WatermarkStyle.SIZES, "%", enabled) { update(style.copy(size = it)) }
    ImageSlider("不透明度", style.opacity, WatermarkStyle.OPACITIES, "%", enabled) { update(style.copy(opacity = it)) }
}

private val borderColors = listOf("白色" to "#FFFFFF", "黑色" to "#1B1B1D", "米白" to "#F1E8D8", "深蓝" to "#253A57")

@Composable
private fun BorderControls(style: BorderStyle, update: (BorderStyle) -> Unit, enabled: Boolean) {
    Choices(BorderPreset.entries, style.preset, enabled, BorderPreset::label) { update(style.copy(preset = it)) }
    ImageSlider("边框宽度（图片短边的比例）", style.thickness, BorderStyle.THICKNESS, "%", enabled) {
        update(style.copy(thickness = it))
    }
    ImageDetail("边框颜色")
    Choices(borderColors, borderColors.find { it.second.equals(style.color, ignoreCase = true) }, enabled, { it.first }) {
        update(style.copy(color = it.second))
    }
    OutlinedTextField(style.color, { update(style.copy(color = it.take(8))) }, Modifier.fillMaxWidth(),
        enabled = enabled, label = { Text("自定义颜色（HEX）") }, placeholder = { Text("#FFFFFF") }, singleLine = true,
        isError = style.argb == null)
    if (style.preset != BorderPreset.UNIFORM) {
        OutlinedTextField(style.caption, { update(style.copy(caption = it.take(BorderStyle.MAX_CAPTION))) }, Modifier.fillMaxWidth(),
            enabled = enabled, label = { Text("说明文字（选填）") }, singleLine = true)
    }
}

@Composable
private fun ImageSlider(label: String, value: Int, range: IntRange, unit: String, enabled: Boolean, update: (Int) -> Unit) {
    ImageDetail("$label $value$unit")
    Slider(value.toFloat(), { update(it.roundToInt()) }, Modifier.semantics { contentDescription = label },
        enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T : Any> Choices(options: List<T>, selected: T?, enabled: Boolean, label: (T) -> String, choose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(option == selected, { choose(option) }, enabled = enabled, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun PixelField(label: String, value: String, enabled: Boolean, modifier: Modifier, edit: (String) -> Unit) {
    OutlinedTextField(value, { input -> edit(input.filter { it in '0'..'9' }.take(10)) }, modifier, enabled = enabled,
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
