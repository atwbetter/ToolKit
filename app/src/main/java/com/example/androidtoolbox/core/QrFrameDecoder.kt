package com.example.androidtoolbox.core

import java.nio.ByteBuffer

/** Reads only the Y plane; honours device padding, buffer offsets, pixel stride and rotation. */
object QrFrameDecoder {
    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotation: Int): String? {
        return decodeResult(buffer, width, height, rowStride, pixelStride, rotation, qrOnly = true)?.text
    }

    fun decodeResult(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        rotation: Int, qrOnly: Boolean = false): CodeResult? {
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
        if (rotation == 0 && pixelStride == 1) {
            if (rowStride == width) bytes.get(luminance)
            else for (y in 0 until height) {
                bytes.position(base + y * rowStride)
                bytes.get(luminance, y * width, width)
            }
            return CodeDecoder.decode(CodeLuminanceSource(width, height, luminance), qrOnly)
        }
        for (y in 0 until height) for (x in 0 until width) {
            val index = when (rotation) {
                90 -> x * height + (height - 1 - y)
                180 -> (height - 1 - y) * width + (width - 1 - x)
                270 -> (width - 1 - x) * height + y
                else -> y * width + x
            }
            luminance[index] = bytes.get(base + y * rowStride + x * pixelStride)
        }
        return CodeDecoder.decode(CodeLuminanceSource(outputWidth, outputHeight, luminance), qrOnly)
    }
}
