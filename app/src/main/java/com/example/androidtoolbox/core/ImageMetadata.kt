package com.example.androidtoolbox.core

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.DataInputStream
import java.io.File
import java.io.IOException

internal data class ImageSource(
    val uri: Uri,
    val name: String,
    val bytes: Long?,
    val mime: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int,
    val dpi: Pair<Double, Double>?,
    val exif: Map<String, String>,
) {
    val swapped = orientation in 5..8
    val width = if (swapped) rawHeight else rawWidth
    val height = if (swapped) rawWidth else rawHeight
}

private val exifFields = linkedMapOf(
    "拍摄时间" to ExifInterface.TAG_DATETIME_ORIGINAL,
    "厂商" to ExifInterface.TAG_MAKE,
    "型号" to ExifInterface.TAG_MODEL,
    "镜头" to ExifInterface.TAG_LENS_MODEL,
    "光圈" to ExifInterface.TAG_F_NUMBER,
    "曝光（秒）" to ExifInterface.TAG_EXPOSURE_TIME,
    "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    "焦距（mm）" to ExifInterface.TAG_FOCAL_LENGTH,
    "方向标记" to ExifInterface.TAG_ORIENTATION,
    "软件" to ExifInterface.TAG_SOFTWARE,
)

internal fun ContentResolver.readImageSource(uri: Uri): ImageSource {
    var name = "图片"
    var bytes: Long? = null
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val file = File(requireNotNull(uri.path))
        name = file.name
        bytes = file.length()
    } else {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) bytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    imageInput(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片，文件可能已损坏或格式不受支持" }
    val mime = bounds.outMimeType ?: getType(uri) ?: "application/octet-stream"
    val exif = if (ExifInterface.isSupportedMimeType(mime)) {
        try { imageInput(uri).use(::ExifInterface) } catch (_: IOException) { null }
    } else null
    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf { it in 1..8 } ?: 1
    val details = buildMap {
        exifFields.forEach { (label, tag) ->
            if (exif?.hasAttribute(tag) == true) {
                exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { put(label, it) }
            }
        }
        exif?.latLong?.let { (lat, lon) -> put("GPS", "$lat, $lon") }
    }
    val density = exif?.let {
        val unit = it.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0)
        val x = it.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0)
        val y = it.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0.0)
        dpiForUnit(unit, x, y, inch = 2, cm = 3)
    } ?: readHeaderDpi(uri, mime)
    return ImageSource(uri, name, bytes, mime, bounds.outWidth, bounds.outHeight, orientation, density, details)
}

internal fun ContentResolver.imageInput(uri: Uri) =
    openInputStream(uri) ?: error("无法打开图片")

private fun dpiForUnit(unit: Int, x: Double, y: Double, inch: Int, cm: Int): Pair<Double, Double>? {
    if (x <= 0 || y <= 0 || !x.isFinite() || !y.isFinite()) return null
    return when (unit) {
        inch -> x to y
        cm -> x * 2.54 to y * 2.54
        else -> null
    }
}

private fun ContentResolver.readHeaderDpi(uri: Uri, mime: String): Pair<Double, Double>? {
    if (mime != "image/png" && mime != "image/jpeg") return null
    return try {
        DataInputStream(imageInput(uri).buffered()).use {
            if (mime == "image/png") it.pngDpi() else it.jpegDpi()
        }
    } catch (_: IOException) { null }
}

private fun DataInputStream.pngDpi(): Pair<Double, Double>? {
    readLong() // The image decoder already verified the signature.
    var remaining = 1024 * 1024
    while (remaining >= 12) {
        val length = readInt()
        val type = readInt()
        if (length !in 0..(remaining - 12) || type == 0x49444154 || type == 0x49454e44) return null
        if (type == 0x70485973 && length == 9) { // pHYs
            val x = readInt().toUInt().toDouble()
            val y = readInt().toUInt().toDouble()
            return if (readUnsignedByte() == 1 && x > 0 && y > 0) x * 0.0254 to y * 0.0254 else null
        }
        if (skipBytes(length + 4) != length + 4) return null
        remaining -= length + 12
    }
    return null
}

private fun DataInputStream.jpegDpi(): Pair<Double, Double>? {
    if (readUnsignedShort() != 0xffd8) return null
    repeat(64) {
        if (readUnsignedByte() != 0xff) return null
        var marker = readUnsignedByte()
        while (marker == 0xff) marker = readUnsignedByte()
        if (marker == 0xda || marker == 0xd9) return null
        val length = readUnsignedShort() - 2
        if (length < 0) return null
        if (marker == 0xe0 && length >= 14) {
            val signature = ByteArray(5).also { readFully(it) }
            if (signature.contentEquals(byteArrayOf(74, 70, 73, 70, 0))) {
                readUnsignedShort() // JFIF version
                val unit = readUnsignedByte()
                return dpiForUnit(unit, readUnsignedShort().toDouble(), readUnsignedShort().toDouble(), inch = 1, cm = 2)
            }
            if (skipBytes(length - 5) != length - 5) return null
        } else if (skipBytes(length) != length) return null
    }
    return null
}
