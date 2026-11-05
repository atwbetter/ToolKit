package com.example.androidtoolbox.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal enum class WatermarkMode(val label: String) { SINGLE("单枚水印"), TILED("全图平铺") }

internal enum class WatermarkPosition(val label: String, val x: Float, val y: Float) {
    TOP_LEFT("左上", 0f, 0f), TOP_CENTER("上中", .5f, 0f), TOP_RIGHT("右上", 1f, 0f),
    CENTER_LEFT("左中", 0f, .5f), CENTER("居中", .5f, .5f), CENTER_RIGHT("右中", 1f, .5f),
    BOTTOM_LEFT("左下", 0f, 1f), BOTTOM_CENTER("下中", .5f, 1f), BOTTOM_RIGHT("右下", 1f, 1f),
}

internal enum class WatermarkColor(val label: String, val argb: Int) {
    WHITE("白色", Color.WHITE), BLACK("黑色", Color.BLACK),
    RED("红色", 0xFFE53935.toInt()), BLUE("蓝色", 0xFF2563EB.toInt()),
}

internal data class WatermarkStyle(
    val text: String = "",
    val mode: WatermarkMode = WatermarkMode.SINGLE,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val color: WatermarkColor = WatermarkColor.WHITE,
    val angle: Int = 0,
    val size: Int = 9,
    val opacity: Int = 70,
    val spacing: Int = 12,
) : Serializable {
    companion object {
        const val MAX_TEXT = 100
        val ANGLES = -90..90
        val SIZES = 4..24
        val OPACITIES = 10..100
        val SPACINGS = 5..30
    }
}

internal object ImageWatermark {
    fun draw(canvas: Canvas, width: Int, height: Int, style: WatermarkStyle) {
        val text = style.text.trim()
        if (text.isEmpty()) return
        val shortSide = minOf(width, height).toFloat()
        val inset = shortSide * .05f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = style.color.argb
            alpha = 255 * style.opacity / 100
            textSize = shortSide * style.size / 100
        }
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        val textWidth = ink.width().coerceAtLeast(1).toFloat()
        val textHeight = ink.height().coerceAtLeast(1).toFloat()
        val tiled = style.mode == WatermarkMode.TILED
        val fit = if (tiled) {
            minOf(1f, shortSide * .75f / textWidth)
        } else {
            val (rotatedWidth, rotatedHeight) = rotatedBounds(textWidth, textHeight, style.angle)
            minOf(1f, (width - 2 * inset) / rotatedWidth, (height - 2 * inset) / rotatedHeight)
        }
        if (fit < 1f) {
            paint.textSize *= fit
            paint.getTextBounds(text, 0, text.length, ink)
        }
        val shadowTone = if (style.color == WatermarkColor.BLACK) 255 else 0
        val shadow = Color.argb(paint.alpha, shadowTone, shadowTone, shadowTone)
        paint.setShadowLayer(paint.textSize * .09f, 0f, paint.textSize * .05f, shadow)
        val inkWidth = ink.width().coerceAtLeast(1).toFloat()
        val inkHeight = ink.height().coerceAtLeast(1).toFloat()
        val stampX = -ink.exactCenterX()
        val stampY = -ink.exactCenterY()
        val saveCount = canvas.save()
        try {
            if (tiled) {
                canvas.translate(width / 2f, height / 2f)
                canvas.rotate(style.angle.toFloat())
                val grid = watermarkGrid(width, height, inkWidth, inkHeight, style.angle, style.spacing)
                for (row in -grid.rows..grid.rows) {
                    val stagger = if (row % 2 == 0) 0f else grid.stepX / 2
                    for (column in -grid.columns..grid.columns) {
                        canvas.drawText(text, stampX + column * grid.stepX + stagger, stampY + row * grid.stepY, paint)
                    }
                }
            } else {
                val (rotatedWidth, rotatedHeight) = rotatedBounds(inkWidth, inkHeight, style.angle)
                val x = inset + rotatedWidth / 2 + (width - 2 * inset - rotatedWidth).coerceAtLeast(0f) * style.position.x
                val y = inset + rotatedHeight / 2 + (height - 2 * inset - rotatedHeight).coerceAtLeast(0f) * style.position.y
                canvas.translate(x, y)
                canvas.rotate(style.angle.toFloat())
                canvas.drawText(text, stampX, stampY, paint)
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
}

internal data class WatermarkGrid(val stepX: Float, val stepY: Float, val columns: Int, val rows: Int)

internal fun watermarkGrid(width: Int, height: Int, stampWidth: Float, stampHeight: Float, angle: Int, spacing: Int): WatermarkGrid {
    val (areaWidth, areaHeight) = rotatedBounds(width.toFloat(), height.toFloat(), angle)
    // Leave room for the corners after rotating the grid, and bound work for very thin images.
    val gap = maxOf(1f, minOf(width, height) * spacing / 100f, maxOf(width, height) / 36f)
    val stepX = stampWidth + gap
    val stepY = stampHeight + gap
    return WatermarkGrid(
        stepX, stepY,
        columns = ceil((areaWidth + stampWidth) / (2 * stepX) + .5f).toInt(),
        rows = ceil((areaHeight + stampHeight) / (2 * stepY)).toInt(),
    )
}

private fun rotatedBounds(width: Float, height: Float, angle: Int): Pair<Float, Float> {
    val radians = Math.toRadians(angle.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    return (width * c + height * s) to (width * s + height * c)
}
