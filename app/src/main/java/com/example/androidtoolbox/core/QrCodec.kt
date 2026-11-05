package com.example.androidtoolbox.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodec {
    fun decode(bitmap: Bitmap): String {
        return BarcodeCodec.decode(bitmap, qrOnly = true).text
    }

    fun matrix(content: String, size: Int = 768, correction: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BitMatrix {
        require(content.isNotBlank()) { "请输入二维码内容" }
        require(content.toByteArray(Charsets.UTF_8).size <= 2953) { "内容过长，请缩短文字或网址" }
        require(size in 256..2048) { "二维码尺寸应为 256–2048 像素" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to correction,
            EncodeHintType.MARGIN to 4,
        )
        return try { MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints) }
        catch (_: com.google.zxing.WriterException) { error("内容超出当前容错等级容量，请缩短内容或降低容错等级") }
    }

    fun generate(content: String, size: Int = 768, correction: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        foreground: Int = Color.rgb(39, 50, 82)): Bitmap {
        return renderMatrix(matrix(content, size, correction), foreground)
    }
}

