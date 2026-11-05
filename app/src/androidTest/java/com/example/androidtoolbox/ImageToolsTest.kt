package com.example.androidtoolbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.androidtoolbox.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class ImageToolsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun exifOrientationSetsCropCoordinatesAndCentimetersConvertToDpi() {
        withFile(".jpg") { file ->
            saveSolid(file, 48, 24, Color.RED, ImageFormat.JPEG)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "3")
                setAttribute(ExifInterface.TAG_X_RESOLUTION, "100/1")
                setAttribute(ExifInterface.TAG_Y_RESOLUTION, "50/1")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:28 08:42:10")
                saveAttributes()
            }
            val source = resolver.readImageSource(Uri.fromFile(file))
            assertEquals(48 to 24, source.rawWidth to source.rawHeight)
            assertEquals(24 to 48, source.width to source.height)
            assertEquals(254.0, checkNotNull(source.dpi).first, 0.01)
            assertEquals(127.0, checkNotNull(source.dpi).second, 0.01)
            assertEquals("2026:10:28 08:42:10", source.exif["拍摄时间"])
            val cropped = ImageTools.edit(resolver, source) { ImageTools.crop(it, 0, 30, 24, 18) }
            try { assertEquals(24 to 18, cropped.width to cropped.height) } finally { cropped.recycle() }
        }
    }

    @Test fun rotationFlipResizeAndStitchKeepPixelOrder() {
        val original = Bitmap.createBitmap(intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE), 2, 2, Bitmap.Config.ARGB_8888)
        val rotated = ImageTools.orient(original, 90, horizontal = true, vertical = false)
        try {
            assertEquals(Color.WHITE, rotated.getPixel(0, 0))
            assertEquals(Color.GREEN, rotated.getPixel(1, 0))
            assertEquals(Color.BLUE, rotated.getPixel(0, 1))
            assertEquals(Color.RED, rotated.getPixel(1, 1))
        } finally { original.recycle(); rotated.recycle() }
        withFile(".png") { first ->
            withFile(".png") { second ->
                saveSolid(first, 6, 8, Color.RED)
                saveSolid(second, 3, 2, Color.BLUE)
                val sources = listOf(first, second).map { resolver.readImageSource(Uri.fromFile(it)) }
                val joined = ImageTools.stitch(resolver, sources, vertical = true)
                val resized = ImageTools.resize(resolver, sources.first(), 3, 4)
                try {
                    assertEquals(3 to 6, joined.width to joined.height)
                    assertEquals(Color.RED, joined.getPixel(1, 3))
                    assertEquals(Color.BLUE, joined.getPixel(1, 4))
                    assertEquals(3 to 4, resized.width to resized.height)
                } finally { joined.recycle(); resized.recycle() }
            }
        }
    }

    @Test fun exportKeepsPngTransparencyAndUsesWhiteForJpeg() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val marked = ImageTools.watermark(bitmap, "Toolbox", WatermarkPosition.CENTER, 80)
        try {
            assertTrue(ImageTools.palette(bitmap).isEmpty())
            assertTrue(ImageTools.palette(marked).isNotEmpty())
            for (format in ImageFormat.entries) {
                val encoded = ByteArrayOutputStream().apply { ImageTools.write(bitmap, format, 90, this) }.toByteArray()
                val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                try {
                    assertEquals(64 to 32, decoded.width to decoded.height)
                    assertEquals(if (format == ImageFormat.JPEG) Color.WHITE else Color.TRANSPARENT, decoded.getPixel(0, 0))
                } finally { decoded.recycle() }
            }
        } finally { bitmap.recycle(); marked.recycle() }
    }

    @Test fun pngDensityAndOriginalFileBase64AndQrRoundTrip() {
        withFile(".png") { file ->
            saveSolid(file, 6, 4, Color.BLUE)
            val plain = resolver.readImageSource(Uri.fromFile(file))
            assertNull(plain.dpi)
            val original = file.readBytes()
            val density = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply {
                    writeBytes("pHYs")
                    writeInt(11811)
                    writeInt(5906)
                    writeByte(1)
                }
            }.toByteArray()
            DataOutputStream(file.outputStream()).use {
                it.write(original, 0, 33) // PNG signature plus IHDR
                it.writeInt(9)
                it.write(density)
                it.writeInt(CRC32().apply { update(density) }.value.toInt())
                it.write(original, 33, original.size - 33)
            }
            val source = resolver.readImageSource(Uri.fromFile(file))
            assertEquals(300.0, checkNotNull(source.dpi).first, 0.02)
            assertEquals(150.0, checkNotNull(source.dpi).second, 0.02)
            val prefix = "data:image/png;base64,"
            withFile(".txt") { encoded ->
                ImageTools.writeBase64(resolver, source, true, encoded)
                val expected = prefix + Base64.getEncoder().encodeToString(file.readBytes())
                assertEquals(expected, encoded.readText())
                val qr = ImageTools.imageQr(resolver, source)
                try { assertEquals(expected, QrCodec.decode(qr)) } finally { qr.recycle() }
            }
            val url = "https://example.com/photo.png"
            val qr = ImageTools.urlQr(url)
            try { assertEquals(url, QrCodec.decode(qr)) } finally { qr.recycle() }
        }
    }

    private fun saveSolid(file: File, width: Int, height: Int, color: Int, format: ImageFormat = ImageFormat.PNG) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { ImageTools.write(bitmap, format, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun withFile(extension: String, test: (File) -> Unit) {
        val file = File.createTempFile("image-test-", extension, context.cacheDir)
        try { test(file) } finally { file.delete() }
    }
}
