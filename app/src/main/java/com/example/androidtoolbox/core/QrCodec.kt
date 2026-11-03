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
        require(bitmap.width.toLong() * bitmap.height <= 8_000_000) { "图片过大" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
            mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true, com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }

    fun generate(content: String, size: Int = 768): Bitmap {
        require(content.isNotBlank()) { "请输入二维码内容" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix: BitMatrix = MultiFormatWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.rgb(39, 50, 82) else Color.WHITE
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}

