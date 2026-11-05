package com.example.androidtoolbox

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.androidtoolbox.core.*
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory

@RunWith(AndroidJUnit4::class)
class BarcodeBitmapTest {
    @Test fun barcodePngWithAndWithoutCaptionCanBeImportedAgain() {
        for (format in CodeFormat.entries) for (caption in listOf(false, true)) for (size in listOf(512, 1024, 2048)) {
            val image = BarcodeCodec.generate(format.example, format, size, caption)
            val bytes = ByteArrayOutputStream().apply { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                val result = BarcodeCodec.decode(decoded)
                assertEquals(if (format == CodeFormat.CODABAR) format.example else BarcodeCodec.normalize(format.example, format), result.text)
            } finally { image.recycle(); decoded.recycle() }
        }
    }

    @Test fun qrPresetsKeepContentThroughPngEncoding() {
        for (color in listOf(Color.BLACK, Color.rgb(39, 50, 82), Color.rgb(20, 85, 69))) {
            val text = QrContent.wifi("中文网络", " pass;word ", "WPA", true)
            val image = QrCodec.generate(text, 512, ErrorCorrectionLevel.H, color)
            try { assertEquals(text, QrCodec.decode(image)) } finally { image.recycle() }
        }
    }
}
