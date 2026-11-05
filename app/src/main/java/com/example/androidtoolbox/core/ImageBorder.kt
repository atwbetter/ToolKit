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
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import java.io.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class BorderPreset(
    val label: String,
    val hasCaption: Boolean = true,
    val hasExif: Boolean = false,
    val defaultColor: String = "#FFFFFF",
    val defaultAlign: Float = 0f,
) {
    UNIFORM("等宽边框", hasCaption = false),
    CAPTION("宽下边框"),
    EXIF("拍摄参数边框", hasExif = true),
    BOTTOM("仅下边框", hasExif = true, defaultColor = "#1B1B1D"),
    COLUMNS("三栏参数", hasExif = true, defaultAlign = .5f),
    GALLERY("上下留白", defaultAlign = .5f),
    BLUR("虚化背景", hasExif = true, defaultAlign = .5f),
}

internal enum class BorderAlignment(val label: String, val fraction: Float?) {
    ORIGINAL("原样", null), LEFT("靠左", 0f), CENTER("居中", .5f), RIGHT("靠右", 1f),
}

internal data class BorderStyle(
    val preset: BorderPreset = BorderPreset.UNIFORM,
    val thickness: Int = 5,
    val color: String = "#FFFFFF",
    val caption: String = "",
    private val textAlignment: BorderAlignment? = null,
) : Serializable {
    val alignment: BorderAlignment get() = textAlignment ?: BorderAlignment.ORIGINAL
    val argb: Int? get() = color.trim().removePrefix("#").takeIf { HEX.matches(it) }
        ?.toIntOrNull(16)?.or(0xff000000.toInt())

    companion object {
        // Keep editor state saved by earlier versions of the app readable.
        private const val serialVersionUID = -4172647632002918530L
        val THICKNESS = 1..20
        const val MAX_CAPTION = 100
        private val HEX = Regex("[0-9a-fA-F]{6}")
    }
}

internal data class BorderLayout(
    val photoWidth: Int,
    val photoHeight: Int,
    val margin: Int,
    val top: Int,
    val footer: Int,
    val lineHeight: Float,
    val rows: List<List<String?>>,
    val style: BorderStyle,
) {
    val width = photoWidth + 2L * margin
    val height = photoHeight.toLong() + top + footer
    val canExport = width <= ImageTools.MAX_EDGE && height <= ImageTools.MAX_EDGE &&
        width * height <= ImageTools.MAX_PIXELS

    fun cells(row: List<String?>): List<BorderTextCell> {
        val inset = if (margin > 0) margin.toFloat() else minOf(photoWidth, photoHeight) * .035f
        val available = (width.toFloat() - 2 * inset).coerceAtLeast(0f)
        val align = style.alignment.fraction
        if (row.size == 1) {
            return row.single()?.let { listOf(BorderTextCell(it, inset, available, align ?: style.preset.defaultAlign)) }.orEmpty()
        }
        val gap = available * .025f
        val columnWidth = (available - 2 * gap) / 3
        return row.mapIndexedNotNull { index, label ->
            label?.let { BorderTextCell(it, inset + index * (columnWidth + gap), columnWidth, align ?: index / 2f) }
        }
    }
}

internal data class BorderTextCell(val text: String, val left: Float, val width: Float, val alignment: Float) {
    fun textStart(textWidth: Float) = left + (width - textWidth).coerceAtLeast(0f) * alignment
}

internal object ImageBorder {
    fun layout(width: Int, height: Int, style: BorderStyle, exif: Map<String, String>): BorderLayout {
        require(width > 0 && height > 0 && style.thickness in BorderStyle.THICKNESS)
        val shortSide = minOf(width, height)
        val edge = (shortSide * (style.thickness / 100.0)).roundToInt().coerceAtLeast(1)
        val preset = style.preset
        val rows = buildList {
            if (preset.hasCaption) style.caption.cleanLine()?.let { add(listOf(it)) }
            if (preset.hasExif) {
                val (camera, settings, date) = photoExif(exif)
                if (preset == BorderPreset.COLUMNS) {
                    if (camera != null || settings != null || date != null) add(listOf(camera, settings, date))
                } else {
                    listOfNotNull(camera, settings, date).forEach { add(listOf(it)) }
                }
            }
        }
        val lineHeight = shortSide * if (preset.hasExif && preset != BorderPreset.COLUMNS) .034f else .048f
        val textHeight = ceil(shortSide * .03 + lineHeight * maxOf(1, rows.size)).toInt()
        val footer = when (preset) {
            BorderPreset.UNIFORM -> edge
            BorderPreset.CAPTION -> maxOf(2 * edge, ceil(shortSide * .16).toInt())
            BorderPreset.BOTTOM -> if (rows.isEmpty()) edge else maxOf(edge, textHeight)
            BorderPreset.GALLERY -> maxOf(edge, ceil(shortSide * .075).toInt(), textHeight)
            BorderPreset.EXIF, BorderPreset.COLUMNS, BorderPreset.BLUR -> maxOf(2 * edge, textHeight)
        }
        val margin = if (preset == BorderPreset.BOTTOM) 0 else edge
        val top = if (preset == BorderPreset.GALLERY) footer else margin
        return BorderLayout(width, height, margin, top, footer, lineHeight, rows, style)
    }

    fun add(bitmap: Bitmap, style: BorderStyle, exif: Map<String, String>): Bitmap {
        val color = requireNotNull(style.argb) { "请输入 6 位十六进制边框颜色" }
        require(style.caption.length <= BorderStyle.MAX_CAPTION) { "说明文字最多 100 个字" }
        val layout = layout(bitmap.width, bitmap.height, style, exif)
        require(layout.canExport) { "加框后尺寸超出限制，请减小边框或先缩小图片" }
        val result = createBitmap(layout.width.toInt(), layout.height.toInt())
        try {
            val backdrop = if (style.preset == BorderPreset.BLUR) blurredBackground(bitmap) else null
            try { draw(Canvas(result), bitmap, layout, color, backdrop) }
            finally { backdrop?.recycle() }
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    fun draw(canvas: Canvas, bitmap: Bitmap, layout: BorderLayout, color: Int, backdrop: Bitmap? = null) {
        val margin = layout.margin.toFloat()
        val top = layout.top.toFloat()
        val photo = RectF(margin, top, margin + layout.photoWidth, top + layout.photoHeight)
        val width = layout.width.toFloat()
        val height = layout.height.toFloat()
        val border = Paint().apply { this.color = color }
        canvas.drawRect(0f, 0f, width, photo.top, border)
        canvas.drawRect(0f, photo.bottom, width, height, border)
        canvas.drawRect(0f, photo.top, photo.left, photo.bottom, border)
        canvas.drawRect(photo.right, photo.top, width, photo.bottom, border)
        if (backdrop != null) {
            val scale = maxOf(width / backdrop.width, height / backdrop.height)
            val x = (width - scale * backdrop.width) / 2
            val y = (height - scale * backdrop.height) / 2
            canvas.withSave {
                clipRect(0f, 0f, width, if (layout.rows.isEmpty()) height else photo.bottom)
                clipOutRect(photo)
                drawBitmap(backdrop, null, RectF(x, y, width - x, height - y),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 195 })
            }
        }
        canvas.drawBitmap(bitmap, null, photo, Paint(Paint.FILTER_BITMAP_FLAG))
        if (layout.rows.isEmpty()) return

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = if (ColorUtils.calculateLuminance(color) > .179) Color.BLACK else Color.WHITE
        }
        val firstLineCenter = photo.bottom + (layout.footer - layout.rows.size * layout.lineHeight) / 2 + layout.lineHeight / 2
        layout.rows.forEachIndexed { index, row ->
            text.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = layout.lineHeight * if (row.size > 1) .60f else .72f
            val baseline = firstLineCenter + index * layout.lineHeight - (text.ascent() + text.descent()) / 2
            layout.cells(row).forEach { cell ->
                val label = TextUtils.ellipsize(cell.text, text, cell.width, TextUtils.TruncateAt.END)
                val x = cell.textStart(text.measureText(label, 0, label.length))
                canvas.drawText(label, 0, label.length, x, baseline, text)
            }
        }
    }

    fun blurredBackground(bitmap: Bitmap): Bitmap {
        val scale = 48f / maxOf(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val sampled = bitmap.scale(width, height)
        val pixels = IntArray(width * height)
        try { sampled.getPixels(pixels, 0, width, 0, 0, width, height) }
        finally { if (sampled !== bitmap) sampled.recycle() }
        val horizontal = IntArray(pixels.size)
        blurPass(pixels, horizontal, width, height, horizontal = true)
        blurPass(horizontal, pixels, width, height, horizontal = false)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(input: IntArray, output: IntArray, width: Int, height: Int, horizontal: Boolean) {
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -3..3) {
                val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val pixel = input[sy * width + sx]
                a += Color.alpha(pixel)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
            }
            output[y * width + x] = Color.argb(a / 7, r / 7, g / 7, b / 7)
        }
    }
}

private data class PhotoExif(val camera: String?, val settings: String?, val date: String?)

internal fun photoExifLines(exif: Map<String, String>): List<String> {
    val (camera, settings, date) = photoExif(exif)
    return listOfNotNull(camera, settings, date)
}

private fun photoExif(exif: Map<String, String>): PhotoExif {
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
    return PhotoExif(camera, settings, date)
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
