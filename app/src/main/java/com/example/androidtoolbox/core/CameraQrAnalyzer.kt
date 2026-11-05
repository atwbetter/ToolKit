package com.example.androidtoolbox.core

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

internal class CameraQrAnalyzer(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val qrOnly: Boolean = false,
    private val onResult: (CodeResult) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var active = true
    private val delivered = AtomicBoolean(false)
    private var lastAttempt = 0L

    fun retry() { delivered.set(false) }

    override fun analyze(image: ImageProxy) {
        try {
            val now = clock()
            if (!active || delivered.get() || now - lastAttempt < 250) return
            lastAttempt = now
            val plane = image.planes.firstOrNull() ?: return
            val result = try {
                QrFrameDecoder.decodeResult(plane.buffer, image.width, image.height, plane.rowStride,
                    plane.pixelStride, image.imageInfo.rotationDegrees, qrOnly)
            } catch (_: IllegalArgumentException) { null }
            if (active && result != null && delivered.compareAndSet(false, true)) onResult(result)
        } finally {
            // Always release the CameraX frame, including skipped or invalid frames.
            image.close()
        }
    }
}
