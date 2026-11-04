package com.example.androidtoolbox.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** Reads only the Y plane; honours device padding, buffer offsets, pixel stride and rotation. */
object QrFrameDecoder {
    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotation: Int): String? {
        require(width > 0 && height > 0 && width.toLong() * height <= 8_000_000) { "相机画面大小无效" }
        require(pixelStride > 0 && rowStride.toLong() >= (width - 1L) * pixelStride + 1) { "相机画面步长无效" }
        require(rotation in setOf(0, 90, 180, 270)) { "相机画面方向无效" }
        val bytes = buffer.duplicate()
        val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + 1
        require(required <= bytes.remaining()) { "相机画面不完整" }
        val base = bytes.position()
        val turned = rotation == 90 || rotation == 270
        val outputWidth = if (turned) height else width
        val outputHeight = if (turned) width else height
        val luminance = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val index = when (rotation) {
                90 -> x * height + (height - 1 - y)
                180 -> (height - 1 - y) * width + (width - 1 - x)
                270 -> (width - 1 - x) * height + y
                else -> y * width + x
            }
            luminance[index] = bytes.get(base + y * rowStride + x * pixelStride)
        }
        val source = PlanarYUVLuminanceSource(luminance, outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, false)
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        for (candidate in listOf(source, source.invert())) {
            try { return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }
            catch (_: ReaderException) { /* No readable QR in this frame. */ }
            finally { reader.reset() }
        }
        return null
    }
}
