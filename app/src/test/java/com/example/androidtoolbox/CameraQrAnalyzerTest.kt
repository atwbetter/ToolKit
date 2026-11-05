package com.example.androidtoolbox

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.example.androidtoolbox.core.CameraQrAnalyzer
import com.example.androidtoolbox.core.QrCodec
import com.example.androidtoolbox.core.CodeResult
import com.example.androidtoolbox.core.BarcodeCodec
import com.example.androidtoolbox.core.CodeFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class CameraQrAnalyzerTest {
    private val text = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
    private val matrix = QrCodec.matrix(text, 384)

    private class Frame(val image: ImageProxy, val closeCount: IntArray)

    private fun frame(truncated: Boolean = false, matrix: BitMatrix = this.matrix): Frame {
        val data = ByteArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0 else 0xff.toByte() }
        val closed = intArrayOf(0)
        val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getBuffer" -> ByteBuffer.wrap(if (truncated) data.copyOf(10) else data)
                "getRowStride" -> matrix.width
                "getPixelStride" -> 1
                else -> error("Unexpected plane method: ${method.name}")
            }
        } as ImageProxy.PlaneProxy
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) { "getRotationDegrees" -> 0; else -> error("Unexpected info method: ${method.name}") }
        } as ImageInfo
        val image = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlanes" -> arrayOf(plane)
                "getWidth" -> matrix.width
                "getHeight" -> matrix.height
                "getImageInfo" -> info
                "close" -> { closed[0]++; null }
                else -> error("Unexpected image method: ${method.name}")
            }
        } as ImageProxy
        return Frame(image, closed)
    }

    @Test fun emitsOnceUntilExplicitRetryAndClosesEveryFrame() {
        var now = 1000L
        val results = mutableListOf<CodeResult>()
        val analyzer = CameraQrAnalyzer({ now }, onResult = results::add)
        val frames = List(3) { frame() }
        analyzer.analyze(frames[0].image)
        now += 300; analyzer.analyze(frames[1].image)
        assertEquals(listOf(text), results.map { it.text })
        analyzer.retry(); now += 300; analyzer.analyze(frames[2].image)
        assertEquals(listOf(text, text), results.map { it.text })
        frames.forEach { assertEquals(1, it.closeCount[0]) }
    }

    @Test fun pausedThrottledAndInvalidFramesAreClosedWithoutCallbacks() {
        var now = 1000L
        val results = mutableListOf<CodeResult>()
        val analyzer = CameraQrAnalyzer({ now }, onResult = results::add)
        val paused = frame(); val invalid = frame(true); val throttled = frame(); val valid = frame()
        analyzer.active = false; analyzer.analyze(paused.image)
        analyzer.active = true; analyzer.analyze(invalid.image)
        now += 50; analyzer.analyze(throttled.image)
        assertTrue(results.isEmpty())
        now += 300; analyzer.analyze(valid.image)
        assertEquals(listOf(text), results.map { it.text })
        listOf(paused, invalid, throttled, valid).forEach { assertEquals(1, it.closeCount[0]) }
    }

    @Test fun callbackFailureStillReleasesCameraFrame() {
        val analyzer = CameraQrAnalyzer({ 1000L }) { throw IllegalStateException("test callback") }
        val frame = frame()
        assertThrows(IllegalStateException::class.java) { analyzer.analyze(frame.image) }
        assertEquals(1, frame.closeCount[0])
    }

    @Test fun returnsBarcodeFormatAndOtpModeIgnoresTheSameBarcode() {
        val barcode = BarcodeCodec.matrix("SCANNER-128", CodeFormat.CODE128)
        val results = mutableListOf<CodeResult>()
        val ordinary = frame(matrix = barcode); val otp = frame(matrix = barcode)
        CameraQrAnalyzer({ 1000L }, onResult = results::add).analyze(ordinary.image)
        assertEquals(CodeResult("SCANNER-128", BarcodeFormat.CODE_128), results.single())
        CameraQrAnalyzer({ 1000L }, qrOnly = true, onResult = results::add).analyze(otp.image)
        assertEquals(1, results.size)
        assertEquals(1, ordinary.closeCount[0]); assertEquals(1, otp.closeCount[0])
    }
}
