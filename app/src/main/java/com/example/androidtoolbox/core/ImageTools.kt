package com.example.androidtoolbox.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.OutputStream
import java.net.URI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp");

    @Suppress("DEPRECATION")
    fun encoder() = when (this) {
        PNG -> Bitmap.CompressFormat.PNG
        JPEG -> Bitmap.CompressFormat.JPEG
        WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}

internal enum class WatermarkPosition(val label: String) { TOP("左上"), CENTER("居中"), BOTTOM("右下") }

internal object ImageTools {
    const val MAX_EDGE = 8192
    const val MAX_PIXELS = 16_000_000
    const val MAX_STITCH_IMAGES = 8
    const val MAX_BASE64_INPUT = 32 * 1024 * 1024
    const val MAX_QR_IMAGE = 2000

    fun validateSize(width: Int, height: Int) {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && width.toLong() * height <= MAX_PIXELS) {
            "尺寸须在 1–8192 px，且总像素不超过 1600 万。大图可先缩小。"
        }
    }

    fun preview(resolver: ContentResolver, source: ImageSource): Bitmap =
        decode(resolver, source, sampleFor(source, 1280, 1_200_000))

    fun edit(resolver: ContentResolver, source: ImageSource, operation: (Bitmap) -> Bitmap): Bitmap {
        validateSize(source.width, source.height)
        return transform(decode(resolver, source, 1), operation)
    }

    fun crop(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= bitmap.width && y.toLong() + height <= bitmap.height) {
            "请输入位于原图范围内的裁剪坐标和尺寸"
        }
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    fun orient(bitmap: Bitmap, degrees: Int, horizontal: Boolean, vertical: Boolean): Bitmap {
        if (degrees == 0 && !horizontal && !vertical) return bitmap
        val matrix = Matrix().apply {
            setScale(if (horizontal) -1f else 1f, if (vertical) -1f else 1f)
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun resize(resolver: ContentResolver, source: ImageSource, width: Int, height: Int): Bitmap {
        validateSize(width, height)
        return transform(decode(resolver, source, sampleForTarget(source, width, height))) {
            it.scale(width, height)
        }
    }

    fun watermark(bitmap: Bitmap, text: String, position: WatermarkPosition, opacity: Int): Bitmap {
        require(text.isNotBlank() && text.length <= 100) { "请输入 1–100 个字的水印文字" }
        require(opacity in 10..100) { "水印不透明度须在 10%–100%" }
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: error("无法创建水印图片")
        val inset = min(bitmap.width, bitmap.height) * 0.05f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = (255 * opacity / 100)
            textSize = min(bitmap.width, bitmap.height) * 0.09f
            textScaleX = min(1f, (bitmap.width - 2 * inset) / measureText(text).coerceAtLeast(1f))
            setShadowLayer(textSize / 10, 0f, textSize / 18, Color.argb(alpha, 0, 0, 0))
        }
        val width = paint.measureText(text)
        val (x, y) = when (position) {
            WatermarkPosition.TOP -> inset to inset - paint.ascent()
            WatermarkPosition.CENTER -> (bitmap.width - width) / 2 to (bitmap.height - paint.ascent() - paint.descent()) / 2
            WatermarkPosition.BOTTOM -> bitmap.width - width - inset to bitmap.height - inset - paint.descent()
        }
        Canvas(output).drawText(text, x, y, paint)
        return output
    }

    fun stitch(resolver: ContentResolver, sources: List<ImageSource>, vertical: Boolean): Bitmap {
        require(sources.size in 2..MAX_STITCH_IMAGES) { "请选择 2–8 张图片" }
        val common = sources.minOf { if (vertical) it.width else it.height }.toDouble()
        val lengths = sources.map { common * if (vertical) it.height.toDouble() / it.width else it.width.toDouble() / it.height }
        val total = lengths.sum()
        val scale = minOf(1.0, MAX_EDGE / common, (MAX_EDGE - sources.size) / total,
            sqrt((MAX_PIXELS - MAX_EDGE * sources.size) / (common * total)))
        val side = (common * scale).roundToInt().coerceAtLeast(1)
        val sides = lengths.map { (it * scale).roundToInt().coerceAtLeast(1) }
        val width = if (vertical) side else sides.sum()
        val height = if (vertical) sides.sum() else side
        validateSize(width, height)
        val result = createBitmap(width, height)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var offset = 0
        try {
            sources.zip(sides).forEach { (source, length) ->
                val rect = if (vertical) Rect(0, offset, side, offset + length) else Rect(offset, 0, offset + length, side)
                val sample = sampleForTarget(source, rect.width(), rect.height(), pixelLimit = 4_000_000)
                val bitmap = decode(resolver, source, sample)
                try { canvas.drawBitmap(bitmap, null, rect, paint) } finally { bitmap.recycle() }
                offset += length
            }
            return result
        } catch (failure: Exception) {
            result.recycle()
            throw failure
        }
    }

    fun write(bitmap: Bitmap, format: ImageFormat, quality: Int, output: OutputStream) {
        if (format != ImageFormat.JPEG || !bitmap.hasAlpha()) {
            check(bitmap.compress(format.encoder(), quality, output)) { "图片编码失败" }
            return
        }
        val opaque = createBitmap(bitmap.width, bitmap.height)
        try {
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
            }
            check(opaque.compress(format.encoder(), quality, output)) { "图片编码失败" }
        } finally { opaque.recycle() }
    }

    fun writeBase64(resolver: ContentResolver, source: ImageSource, dataUrl: Boolean, file: File) {
        require(source.bytes == null || source.bytes <= MAX_BASE64_INPUT) { "Base64 导出支持最大 32 MiB 的原图" }
        file.outputStream().buffered().use { output ->
            if (dataUrl) output.write(dataPrefix(source).toByteArray(Charsets.US_ASCII))
            Base64OutputStream(output, Base64.NO_WRAP or Base64.NO_CLOSE).use { encoder ->
                resolver.imageInput(source.uri).use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BASE64_INPUT) { "Base64 导出支持最大 32 MiB 的原图" }
                        encoder.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    fun imageQr(resolver: ContentResolver, source: ImageSource): Bitmap {
        val bytes = resolver.imageInput(source.uri).use { input ->
            val buffer = ByteArray(MAX_QR_IMAGE + 1)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            require(size in 1..MAX_QR_IMAGE) { "图片超过 2000 字节，请先缩小并压缩，或使用图片网址" }
            buffer.copyOf(size)
        }
        return qr(dataPrefix(source) + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun urlQr(value: String): Bitmap {
        val text = value.trim()
        val uri = runCatching { URI(text) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "请输入完整的 HTTP 或 HTTPS 图片网址"
        }
        return qr(text)
    }

    fun palette(bitmap: Bitmap): List<Int> {
        val counts = IntArray(4096)
        val red = IntArray(counts.size)
        val green = IntArray(counts.size)
        val blue = IntArray(counts.size)
        val step = (maxOf(bitmap.width, bitmap.height) / 96).coerceAtLeast(1)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap[x, y]
                if (Color.alpha(pixel) < 32) continue
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val index = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                counts[index]++
                red[index] += r
                green[index] += g
                blue[index] += b
            }
        }
        return counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(6).map {
            Color.rgb(red[it] / counts[it], green[it] / counts[it], blue[it] / counts[it])
        }
    }

    private fun qr(text: String) = QrCodec.generate(text, correction = ErrorCorrectionLevel.L, foreground = Color.BLACK)

    private fun dataPrefix(source: ImageSource) = "data:${source.mime};base64,"

    private fun sampleFor(source: ImageSource, edgeLimit: Int, pixelLimit: Int): Int {
        var sample = 1
        while (true) {
            val width = ceil(source.width.toDouble() / sample).toInt()
            val height = ceil(source.height.toDouble() / sample).toInt()
            if (maxOf(width, height) <= edgeLimit && width.toLong() * height <= pixelLimit) return sample
            sample *= 2
        }
    }

    private fun sampleForTarget(source: ImageSource, width: Int, height: Int, pixelLimit: Int = MAX_PIXELS): Int {
        var sample = 1
        while (source.width / (2 * sample) >= width && source.height / (2 * sample) >= height) sample *= 2
        return maxOf(sample, sampleFor(source, MAX_EDGE, pixelLimit))
    }

    private fun decode(resolver: ContentResolver, source: ImageSource, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.imageInput(source.uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("图片解码失败")
        return transform(bitmap) {
            when (source.orientation) {
                2 -> orient(it, 0, horizontal = true, vertical = false)
                3 -> orient(it, 180, horizontal = false, vertical = false)
                4 -> orient(it, 0, horizontal = false, vertical = true)
                5 -> orient(it, 270, horizontal = true, vertical = false)
                6 -> orient(it, 90, horizontal = false, vertical = false)
                7 -> orient(it, 90, horizontal = true, vertical = false)
                8 -> orient(it, 270, horizontal = false, vertical = false)
                else -> it
            }
        }
    }

    private fun transform(bitmap: Bitmap, operation: (Bitmap) -> Bitmap): Bitmap {
        var result: Bitmap? = null
        try {
            return operation(bitmap).also { result = it }
        } finally {
            if (bitmap !== result) bitmap.recycle()
        }
    }
}
