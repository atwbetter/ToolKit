package com.example.androidtoolbox.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import java.io.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class BorderPreset(val label: String) {
    UNIFORM("等宽边框"), CAPTION("宽下边框"), EXIF("拍摄参数边框"),
}

internal data class BorderStyle(
    val preset: BorderPreset = BorderPreset.UNIFORM,
    val thickness: Int = 5,
    val color: String = "#FFFFFF",
    val caption: String = "",
) : Serializable {
    val argb: Int? get() = color.trim().removePrefix("#").takeIf { HEX.matches(it) }
        ?.toIntOrNull(16)?.or(0xff000000.toInt())

    companion object {
        val THICKNESS = 1..20
        const val MAX_CAPTION = 100
        private val HEX = Regex("[0-9a-fA-F]{6}")
    }
}

internal data class BorderLayout(
    val photoWidth: Int,
    val photoHeight: Int,
    val margin: Int,
    val footer: Int,
    val lineHeight: Float,
    val lines: List<String>,
) {
    val width = photoWidth + 2L * margin
    val height = photoHeight.toLong() + margin + footer
    val canExport = width <= ImageTools.MAX_EDGE && height <= ImageTools.MAX_EDGE &&
        width * height <= ImageTools.MAX_PIXELS
}

internal object ImageBorder {
    fun layout(width: Int, height: Int, style: BorderStyle, exif: Map<String, String>): BorderLayout {
        require(width > 0 && height > 0 && style.thickness in BorderStyle.THICKNESS)
        val shortSide = minOf(width, height)
        val margin = (shortSide * (style.thickness / 100.0)).roundToInt().coerceAtLeast(1)
        val lines = buildList {
            if (style.preset != BorderPreset.UNIFORM) style.caption.cleanLine()?.let(::add)
            if (style.preset == BorderPreset.EXIF) addAll(photoExifLines(exif))
        }
        val lineHeight = shortSide * if (style.preset == BorderPreset.EXIF) .034f else .048f
        val footer = when (style.preset) {
            BorderPreset.UNIFORM -> margin
            BorderPreset.CAPTION -> maxOf(2 * margin, ceil(shortSide * .16).toInt())
            BorderPreset.EXIF -> maxOf(2 * margin, ceil(shortSide * .03 + lineHeight * maxOf(1, lines.size)).toInt())
        }
        return BorderLayout(width, height, margin, footer, lineHeight, lines)
    }

    fun add(bitmap: Bitmap, style: BorderStyle, exif: Map<String, String>): Bitmap {
        val color = requireNotNull(style.argb) { "请输入 6 位十六进制边框颜色" }
        require(style.caption.length <= BorderStyle.MAX_CAPTION) { "说明文字最多 100 个字" }
        val layout = layout(bitmap.width, bitmap.height, style, exif)
        require(layout.canExport) { "加框后尺寸超出限制，请减小边框或先缩小图片" }
        val result = createBitmap(layout.width.toInt(), layout.height.toInt())
        try {
            draw(Canvas(result), bitmap, layout, color)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    fun draw(canvas: Canvas, bitmap: Bitmap, layout: BorderLayout, color: Int) {
        val margin = layout.margin.toFloat()
        val photo = RectF(margin, margin, margin + layout.photoWidth, margin + layout.photoHeight)
        val width = layout.width.toFloat()
        val height = layout.height.toFloat()
        val border = Paint().apply { this.color = color }
        canvas.drawRect(0f, 0f, width, photo.top, border)
        canvas.drawRect(0f, photo.bottom, width, height, border)
        canvas.drawRect(0f, photo.top, photo.left, photo.bottom, border)
        canvas.drawRect(photo.right, photo.top, width, photo.bottom, border)
        canvas.drawBitmap(bitmap, null, photo, Paint(Paint.FILTER_BITMAP_FLAG))
        if (layout.lines.isEmpty()) return

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = if (ColorUtils.calculateLuminance(color) > .179) Color.BLACK else Color.WHITE
            textSize = layout.lineHeight * .72f
        }
        val firstLineCenter = photo.bottom + (layout.footer - layout.lines.size * layout.lineHeight) / 2 + layout.lineHeight / 2
        layout.lines.forEachIndexed { index, line ->
            text.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val label = TextUtils.ellipsize(line, text, layout.photoWidth.toFloat(), TextUtils.TruncateAt.END)
            val baseline = firstLineCenter + index * layout.lineHeight - (text.ascent() + text.descent()) / 2
            canvas.drawText(label, 0, label.length, margin, baseline, text)
        }
    }
}

internal fun photoExifLines(exif: Map<String, String>): List<String> {
    val make = exif["厂商"].cleanLine()
    val model = exif["型号"].cleanLine()
    val camera = if (make != null && model?.startsWith(make, ignoreCase = true) == true) model
        else listOfNotNull(make, model).joinToString(" ").ifEmpty { null }
    val date = exif["拍摄时间"].cleanLine()?.let {
        if (it.length >= 10 && it[4] == ':' && it[7] == ':') it.take(10).replace(':', '-') + it.drop(10) else it
    }
    val settings = listOfNotNull(
        exif["焦距（mm）"].positiveNumber()?.let { "${it.asPhotoNumber()} mm" },
        exif["光圈"].positiveNumber()?.let { "f/${it.asPhotoNumber()}" },
        exif["曝光（秒）"].positiveNumber()?.let(::shutterLabel),
        exif["ISO"].positiveNumber()?.let { "ISO ${it.asPhotoNumber()}" },
    ).joinToString("  ·  ").ifEmpty { null }
    return listOfNotNull(camera, settings, date)
}

private fun String?.cleanLine(): String? = this?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.positiveNumber(): Double? {
    val value = cleanLine() ?: return null
    val numerator = value.substringBefore('/').toDoubleOrNull() ?: return null
    val denominator = value.substringAfter('/', "1").toDoubleOrNull() ?: return null
    return (numerator / denominator).takeIf { it.isFinite() && it > 0 }
}

private fun Double.asPhotoNumber() = BigDecimal.valueOf(this).setScale(6, RoundingMode.HALF_UP)
    .stripTrailingZeros().toPlainString()

private fun shutterLabel(seconds: Double): String {
    val inverse = 1 / seconds
    val nearest = inverse.roundToInt()
    return if (inverse in 2.0..1_000_000.0 && abs(inverse - nearest) <= inverse * .01) "1/$nearest s"
        else "${seconds.asPhotoNumber()} s"
}
