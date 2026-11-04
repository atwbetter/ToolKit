package com.example.androidtoolbox

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.example.androidtoolbox.core.CameraQrAnalyzer
import com.example.androidtoolbox.core.QrCodec
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class CameraQrAnalyzerTest {
    private val text = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
    private val matrix = QrCodec.matrix(text, 384)
    private val data = ByteArray(384 * 384) { i -> if (matrix[i % 384, i / 384]) 0 else 0xff.toByte() }

    private class Frame(val image: ImageProxy, val closeCount: IntArray)

    private fun frame(truncated: Boolean = false): Frame {
        val closed = intArrayOf(0)
        val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getBuffer" -> ByteBuffer.wrap(if (truncated) data.copyOf(10) else data)
                "getRowStride" -> 384
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
                "getWidth", "getHeight" -> 384
                "getImageInfo" -> info
                "close" -> { closed[0]++; null }
                else -> error("Unexpected image method: ${method.name}")
            }
        } as ImageProxy
        return Frame(image, closed)
    }

    @Test fun emitsOnceUntilExplicitRetryAndClosesEveryFrame() {
        var now = 1000L
        val results = mutableListOf<String>()
        val analyzer = CameraQrAnalyzer({ now }, results::add)
        val frames = List(3) { frame() }
        analyzer.analyze(frames[0].image)
        now += 300; analyzer.analyze(frames[1].image)
        assertEquals(listOf(text), results)
        analyzer.retry(); now += 300; analyzer.analyze(frames[2].image)
        assertEquals(listOf(text, text), results)
        frames.forEach { assertEquals(1, it.closeCount[0]) }
    }

    @Test fun pausedThrottledAndInvalidFramesAreClosedWithoutCallbacks() {
        var now = 1000L
        val results = mutableListOf<String>()
        val analyzer = CameraQrAnalyzer({ now }, results::add)
        val paused = frame(); val invalid = frame(true); val throttled = frame(); val valid = frame()
        analyzer.active = false; analyzer.analyze(paused.image)
        analyzer.active = true; analyzer.analyze(invalid.image)
        now += 50; analyzer.analyze(throttled.image)
        assertTrue(results.isEmpty())
        now += 300; analyzer.analyze(valid.image)
        assertEquals(listOf(text), results)
        listOf(paused, invalid, throttled, valid).forEach { assertEquals(1, it.closeCount[0]) }
    }

    @Test fun callbackFailureStillReleasesCameraFrame() {
        val analyzer = CameraQrAnalyzer({ 1000L }) { throw IllegalStateException("test callback") }
        val frame = frame()
        assertThrows(IllegalStateException::class.java) { analyzer.analyze(frame.image) }
        assertEquals(1, frame.closeCount[0])
    }
}
