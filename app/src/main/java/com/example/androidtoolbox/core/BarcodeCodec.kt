package com.example.androidtoolbox.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.*
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

enum class CodeFormat(val label: String, val zxing: BarcodeFormat, val rule: String, val example: String) {
    CODE128("Code 128", BarcodeFormat.CODE_128, "1–80 个可打印英文字符或数字，保留首尾空格", "TOOLBOX-2026"),
    EAN13("EAN-13", BarcodeFormat.EAN_13, "12 位数字自动补校验位，或输入完整 13 位商品码", "690123456789"),
    EAN8("EAN-8", BarcodeFormat.EAN_8, "7 位数字自动补校验位，或输入完整 8 位商品码", "9638507"),
    UPCA("UPC-A", BarcodeFormat.UPC_A, "11 位数字自动补校验位，或输入完整 12 位商品码", "01234567890"),
    CODE39("Code 39", BarcodeFormat.CODE_39, "1–80 位：大写字母、数字、空格及 - . $ / + %", "TOOLBOX-2026"),
    ITF("ITF", BarcodeFormat.ITF, "2–80 位数字，位数必须为偶数", "1234567890"),
    CODABAR("Codabar", BarcodeFormat.CODABAR, "2–80 位数字或 - $ : / . +，自动添加 A 起止符", "12345678"),
}

data class CodeResult(val text: String, val format: BarcodeFormat) {
    val formatLabel: String get() = if (format == BarcodeFormat.QR_CODE) "二维码 · QR Code"
        else CodeFormat.entries.firstOrNull { it.zxing == format }?.label ?: format.name.replace('_', '-')
    val parsed get() = ResultParser.parseResult(Result(text, null, null, format))
    val typeLabel: String get() = when (parsed.type) {
        ParsedResultType.URI -> "网址"
        ParsedResultType.WIFI -> "Wi-Fi 网络"
        ParsedResultType.ADDRESSBOOK -> "联系人"
        ParsedResultType.EMAIL_ADDRESS -> "邮件"
        ParsedResultType.SMS -> "短信"
        ParsedResultType.TEL -> "电话号码"
        ParsedResultType.PRODUCT -> "商品编号"
        ParsedResultType.ISBN -> "图书 ISBN"
        ParsedResultType.GEO -> "地理位置"
        ParsedResultType.CALENDAR -> "日程"
        else -> "文本"
    }
}

/** Shared by image imports and camera frames. No platform bitmap dependency in decoding. */
object CodeDecoder {
    private val formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E, BarcodeFormat.ITF, BarcodeFormat.CODABAR)

    fun decode(source: LuminanceSource, qrOnly: Boolean = false): CodeResult? {
        val reader = MultiFormatReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.ALLOWED_LENGTHS to IntArray(40) { (it + 1) * 2 },
            DecodeHintType.POSSIBLE_FORMATS to if (qrOnly) listOf(BarcodeFormat.QR_CODE) else formats)
        for (candidate in listOf(source, source.invert())) {
            try {
                val result = reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints)
                return CodeResult(result.text, result.barcodeFormat)
            } catch (_: ReaderException) { /* Try the opposite polarity. */ }
            finally { reader.reset() }
        }
        // Dense, perfectly aligned screenshots can confuse geometric detection. Extract
        // their modules directly as a second attempt; QR error correction still validates them.
        for (candidate in listOf(source, source.invert())) {
            val result = decodePureQr(candidate)
            if (result != null) return result
        }
        return null
    }

    internal fun decodePureQr(source: LuminanceSource): CodeResult? = try {
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
        CodeResult(result.text, result.barcodeFormat)
    } catch (_: ReaderException) { null }
}

/** Rotation lets ZXing's one-dimensional reader also scan vertical barcodes. */
internal class CodeLuminanceSource(width: Int, height: Int, private val bytes: ByteArray) : LuminanceSource(width, height) {
    init { require(width > 0 && height > 0 && width.toLong() * height == bytes.size.toLong()) }
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        return (if (row == null || row.size < width) ByteArray(width) else row).also {
            bytes.copyInto(it, 0, y * width, (y + 1) * width)
        }
    }
    override fun getMatrix(): ByteArray = bytes
    override fun isRotateSupported() = true
    override fun rotateCounterClockwise(): LuminanceSource {
        val rotated = ByteArray(bytes.size)
        for (y in 0 until height) for (x in 0 until width) rotated[(width - 1 - x) * height + y] = bytes[y * width + x]
        return CodeLuminanceSource(height, width, rotated)
    }
}

object BarcodeCodec {
    fun normalize(content: String, format: CodeFormat): String {
        require(content.isNotBlank()) { "请输入条形码内容" }
        when (format) {
            CodeFormat.EAN13 -> return retail(content, 12)
            CodeFormat.EAN8 -> return retail(content, 7)
            CodeFormat.UPCA -> return retail(content, 11)
            CodeFormat.CODE128 -> require(content.length <= 80 && content.all { it in ' '..'~' }) { format.rule }
            CodeFormat.CODE39 -> require(content.length <= 80 && content.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ -.\$/+%" }) { format.rule }
            CodeFormat.ITF -> require(content.length in 2..80 && content.length % 2 == 0 && content.all { it in '0'..'9' }) { format.rule }
            CodeFormat.CODABAR -> {
                require(content.length in 2..80 && content.all { it in "0123456789-\$:/.+" }) { format.rule }
                return "A${content}A"
            }
        }
        return content
    }

    private fun retail(value: String, payloadLength: Int): String {
        require(value.length in payloadLength..payloadLength + 1 && value.all { it in '0'..'9' }) {
            "请输入 $payloadLength 或 ${payloadLength + 1} 位数字"
        }
        val payload = value.take(payloadLength)
        val sum = payload.reversed().mapIndexed { index, digit -> (digit - '0') * if (index % 2 == 0) 3 else 1 }.sum()
        val complete = payload + ((10 - sum % 10) % 10)
        require(value.length == payloadLength || value == complete) { "校验位不正确，应为 ${complete.last()}" }
        return complete
    }

    fun matrix(content: String, format: CodeFormat, width: Int = 1536, height: Int = 480): BitMatrix {
        require(width in 512..3072 && height in 160..1024) { "条形码尺寸无效" }
        val value = normalize(content, format)
        // A quiet zone of at least ten modules on each side, including dense Code 39 input.
        return MultiFormatWriter().encode(value, format.zxing, width, height, mapOf(EncodeHintType.MARGIN to 40))
    }

    fun generate(content: String, format: CodeFormat, width: Int, showText: Boolean): Bitmap {
        val value = normalize(content, format)
        val matrix = matrix(content, format, width, (width / 4).coerceAtLeast(160))
        val footer = if (showText) width / 18 else 0
        val bitmap = renderMatrix(matrix, Color.BLACK, footer)
        if (showText) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
                textSize = width / 35f
            }
            val available = matrix.width * 0.92f
            if (paint.measureText(value) > available) paint.textSize *= available / paint.measureText(value)
            Canvas(bitmap).drawText(value, matrix.width / 2f,
                matrix.height + footer / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        return bitmap
    }

    fun decode(bitmap: Bitmap, qrOnly: Boolean = false): CodeResult {
        require(bitmap.width.toLong() * bitmap.height <= 8_000_000) { "图片过大" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size) { i ->
            val pixel = pixels[i]
            val alpha = pixel ushr 24
            val gray = (((pixel shr 16 and 255) + (pixel shr 8 and 255) * 2 + (pixel and 255)) / 4)
            ((gray * alpha + 255 * (255 - alpha)) / 255).toByte()
        }
        return CodeDecoder.decode(CodeLuminanceSource(bitmap.width, bitmap.height, bytes), qrOnly)
            ?: error(if (qrOnly) "图片中未找到可识别的二维码" else "图片中未找到支持的二维码或条形码，请选择清晰、完整的图片")
    }
}

internal fun renderMatrix(matrix: BitMatrix, foreground: Int, footer: Int = 0): Bitmap {
    val width = matrix.width; val height = matrix.height + footer
    val pixels = IntArray(width * height) { Color.WHITE }
    for (y in 0 until matrix.height) for (x in 0 until width) if (matrix[x, y]) pixels[y * width + x] = foreground
    // The barcode caption is drawn onto this bitmap with Canvas.
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
