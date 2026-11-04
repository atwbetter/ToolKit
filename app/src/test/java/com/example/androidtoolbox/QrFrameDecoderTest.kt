package com.example.androidtoolbox

import com.example.androidtoolbox.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class QrFrameDecoderTest {
    private val otp = "otpauth://totp/Example:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"

    private data class Frame(val buffer: ByteBuffer, val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int)

    private fun frame(content: String, invert: Boolean = false, padding: Int = 0, pixelStride: Int = 1, prefix: Int = 0): Frame {
        val qr = QrCodec.matrix(content, 512)
        val width = 640; val height = 768
        val rowStride = width * pixelStride + padding
        // Real camera buffers may omit padding following the last pixel of the last row.
        val data = ByteArray(prefix + (height - 1) * rowStride + (width - 1) * pixelStride + 1) { 0x55 }
        val left = (width - qr.width) / 2; val top = (height - qr.height) / 2
        for (y in 0 until height) for (x in 0 until width) {
            val dark = x in left until left + qr.width && y in top until top + qr.height && qr[x - left, y - top]
            data[prefix + y * rowStride + x * pixelStride] = if (dark != invert) 0 else 0xff.toByte()
        }
        return Frame(ByteBuffer.wrap(data).apply { position(prefix) }.asReadOnlyBuffer(), width, height, rowStride, pixelStride)
    }

    private fun decode(frame: Frame, rotation: Int = 0) = QrFrameDecoder.decode(frame.buffer, frame.width,
        frame.height, frame.rowStride, frame.pixelStride, rotation)

    @Test fun readsOtpFromAllCameraRotations() {
        val frame = frame(otp)
        for (rotation in listOf(0, 90, 180, 270)) assertEquals(otp, decode(frame, rotation))
    }

    @Test fun honoursRowPaddingPixelStrideAndBufferOffsetWithoutMutatingBuffer() {
        val frame = frame(otp, padding = 37, pixelStride = 2, prefix = 19)
        val position = frame.buffer.position(); val limit = frame.buffer.limit()
        assertEquals(otp, decode(frame, 90))
        assertEquals(position, frame.buffer.position()); assertEquals(limit, frame.buffer.limit())
    }

    @Test fun supportsInvertedQrOnDarkScreensAndUnicodeText() {
        val text = "离线扫码 · 🔐 + % 中文"
        assertEquals(text, decode(frame(text, invert = true, padding = 13), 270))
    }

    @Test fun decodesGoogleBatchQrFromPaddedCameraFrames() {
        val accounts = (1..12).map { OtpAccount(UUID.randomUUID().toString(), "平台 $it", "user$it@example.com", "JBSWY3DPEHPK3PXP") }
        var batch = OtpMigrationBatch()
        OtpMigration.encode(accounts).forEach { uri ->
            val read = decode(frame(uri, padding = 32, prefix = 7), 180)
            assertEquals(uri, read)
            batch = batch.add(OtpMigration.decode(read!!))
        }
        assertTrue(batch.complete)
        accounts.zip(batch.accounts).forEach { (before, after) ->
            assertEquals(before.copy(id = after.id), after)
            assertEquals(OtpEngine.code(before, 59_000), OtpEngine.code(after, 59_000))
        }
    }

    @Test fun emptyAndTruncatedFramesDoNotBecomeResults() {
        assertNull(QrFrameDecoder.decode(ByteBuffer.wrap(ByteArray(100 * 100) { 0xff.toByte() }), 100, 100, 100, 1, 0))
        val frame = frame(otp)
        val truncated = frame.buffer.duplicate().apply { limit(limit() - 1) }
        assertThrows(IllegalArgumentException::class.java) {
            QrFrameDecoder.decode(truncated, frame.width, frame.height, frame.rowStride, frame.pixelStride, 0)
        }
    }

    @Test fun rejectsUnsafeFrameDimensionsStridesAndRotations() {
        val buffer = ByteBuffer.wrap(ByteArray(100))
        for (args in listOf(listOf(0, 10, 10, 1, 0), listOf(10, 10, 5, 1, 0),
            listOf(10, 10, 10, 0, 0), listOf(10, 10, 10, 1, 45), listOf(Int.MAX_VALUE, 2, Int.MAX_VALUE, 1, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                QrFrameDecoder.decode(buffer, args[0], args[1], args[2], args[3], args[4])
            }
        }
    }
}
