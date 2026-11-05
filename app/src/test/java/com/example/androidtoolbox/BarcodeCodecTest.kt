package com.example.androidtoolbox

import com.example.androidtoolbox.core.*
import com.google.zxing.*
import com.google.zxing.client.result.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class BarcodeCodecTest {
    private fun source(matrix: BitMatrix): LuminanceSource = CodeLuminanceSource(matrix.width, matrix.height,
        ByteArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) 0 else 255.toByte() })

    @Test fun fullSizeImageDataUrlFitsAndDecodesAsQr() {
        val image = ByteArray(ImageTools.MAX_QR_IMAGE) { (it * 193).toByte() }
        val payload = "data:image/png;base64," + Base64.getEncoder().encodeToString(image)
        val matrix = QrCodec.matrix(payload, correction = ErrorCorrectionLevel.L)
        val read = CodeDecoder.decode(source(matrix), qrOnly = true)
        assertEquals(payload, read?.text)
        assertArrayEquals(image, Base64.getDecoder().decode(read!!.text.substringAfter(',')))
    }

    @Test fun retailCheckDigitsMatchKnownProductNumbersAndPreserveLeadingZeroes() {
        for ((format, complete) in listOf(CodeFormat.EAN13 to "4006381333931", CodeFormat.EAN8 to "96385074", CodeFormat.UPCA to "036000291452")) {
            assertEquals(complete, BarcodeCodec.normalize(complete.dropLast(1), format))
            assertEquals(complete, BarcodeCodec.normalize(complete, format))
            val wrong = complete.dropLast(1) + (if (complete.last() == '0') '1' else '0')
            assertThrows(IllegalArgumentException::class.java) { BarcodeCodec.normalize(wrong, format) }
        }
    }

    @Test fun allSevenFormatsRoundTripThroughActualRenderedModules() {
        CodeFormat.entries.forEach { format ->
            val result = CodeDecoder.decode(source(BarcodeCodec.matrix(format.example, format)))!!
            val expected = if (format == CodeFormat.CODABAR) format.example else BarcodeCodec.normalize(format.example, format)
            assertEquals(format.label, expected, result.text)
            assertEquals(format.zxing, result.format)
        }
    }

    @Test fun barcodesDecodeFromAllRotationsAndInvertedImages() {
        for (format in CodeFormat.entries) {
            var image = source(BarcodeCodec.matrix(format.example, format, 1024, 320))
            repeat(4) {
                assertNotNull("${format.label}, rotation $it", CodeDecoder.decode(image))
                assertNotNull("${format.label}, inverted rotation $it", CodeDecoder.decode(image.invert()))
                image = image.rotateCounterClockwise()
            }
        }
    }

    @Test fun invalidBarcodeInputsFailBeforeEncoding() {
        val cases = listOf(CodeFormat.CODE128 to "中文", CodeFormat.CODE128 to "a\nb", CodeFormat.CODE128 to "x".repeat(81),
            CodeFormat.CODE39 to "lowercase", CodeFormat.CODE39 to "A*B", CodeFormat.ITF to "123",
            CodeFormat.ITF to "12a4", CodeFormat.CODABAR to "A123B", CodeFormat.CODABAR to "1", CodeFormat.EAN13 to "12345678901234",
            CodeFormat.EAN8 to "１２３４５６７", CodeFormat.UPCA to "03600029145 ")
        cases.forEach { (format, text) -> assertThrows(format.label, IllegalArgumentException::class.java) { BarcodeCodec.matrix(text, format) } }
        CodeFormat.entries.forEach { format -> assertThrows(IllegalArgumentException::class.java) { BarcodeCodec.normalize("", format) } }
    }

    @Test fun code128RetainsSpacesAndPunctuationAndShortItfIsReadable() {
        val text = "  ABC-123 / +%  "
        assertEquals(text, CodeDecoder.decode(source(BarcodeCodec.matrix(text, CodeFormat.CODE128)))!!.text)
        for (textItf in listOf("12", "1234", "123456"))
            assertEquals(textItf, CodeDecoder.decode(source(BarcodeCodec.matrix(textItf, CodeFormat.ITF)))!!.text)
    }

    @Test fun shortestAndLongestSupportedBarcodesRemainReadableAtSmallSize() {
        for ((format, samples) in listOf(CodeFormat.CODE128 to listOf("A", "X".repeat(80)),
            CodeFormat.CODE39 to listOf("A", "X".repeat(80)), CodeFormat.ITF to listOf("12", "12".repeat(40)),
            CodeFormat.CODABAR to listOf("12", "12".repeat(40)))) {
            for (text in samples) assertEquals("${format.label}: ${text.length}", text,
                CodeDecoder.decode(source(BarcodeCodec.matrix(text, format, 512, 160)))?.text)
        }
    }

    @Test fun denseBarcodeHasQuietZoneAndNoClippedModulesAtSmallRequestedWidth() {
        val text = "Z".repeat(80)
        val matrix = BarcodeCodec.matrix(text, CodeFormat.CODE39, 512, 160)
        assertTrue(matrix.width > 512)
        for (x in 0 until 20) { assertFalse(matrix[x, 80]); assertFalse(matrix[matrix.width - 1 - x, 80]) }
        assertEquals(text, CodeDecoder.decode(source(matrix))!!.text)
    }

    @Test fun cameraBarcodeUsesPaddedRowsBufferOffsetsAndRotationWithoutChangingInput() {
        val matrix = BarcodeCodec.matrix("CAMERA-2026", CodeFormat.CODE128, 1024, 320)
        val rowStride = matrix.width * 2 + 19
        val bytes = ByteArray(13 + rowStride * matrix.height) { 255.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width)
            bytes[13 + y * rowStride + x * 2] = if (matrix[x, y]) 0 else 255.toByte()
        val buffer = ByteBuffer.wrap(bytes).apply { position(13) }.asReadOnlyBuffer()
        for (rotation in listOf(0, 90, 180, 270)) {
            val result = QrFrameDecoder.decodeResult(buffer, matrix.width, matrix.height, rowStride, 2, rotation)!!
            assertEquals("CAMERA-2026", result.text); assertEquals(BarcodeFormat.CODE_128, result.format)
        }
        assertEquals(13, buffer.position())
        assertNull(QrFrameDecoder.decodeResult(buffer, matrix.width, matrix.height, rowStride, 2, 0, qrOnly = true))
    }

    @Test fun qrOnlyDoesNotAcceptBarcodesAndExtraReadFormatsWork() {
        val barcode = source(BarcodeCodec.matrix("12345678", CodeFormat.CODE128))
        assertNull(CodeDecoder.decode(barcode, qrOnly = true))
        for ((format, text) in listOf(BarcodeFormat.CODE_93 to "READ-93", BarcodeFormat.UPC_E to "0123456")) {
            val decoded = CodeDecoder.decode(source(MultiFormatWriter().encode(text, format, 1024, 320,
                mapOf(EncodeHintType.MARGIN to 40))))
            assertNotNull("Cannot decode $format", decoded)
            val result = decoded!!
            assertEquals(format, result.format)
            assertTrue(result.text.startsWith(text))
        }
    }

    @Test fun blankImagesAndUnsafeDimensionsAreRejected() {
        assertNull(CodeDecoder.decode(CodeLuminanceSource(100, 100, ByteArray(10000) { 255.toByte() })))
        assertThrows(IllegalArgumentException::class.java) { BarcodeCodec.matrix("123", CodeFormat.CODE128, 0, 320) }
        assertThrows(IllegalArgumentException::class.java) { QrCodec.matrix("abc", 4096) }
        assertThrows(IllegalArgumentException::class.java) { QrCodec.matrix("中".repeat(1000)) }
    }

    @Test fun everyQrCorrectionLevelRoundTripsUnicodeAndKeepsQuietZone() {
        for (level in ErrorCorrectionLevel.values()) {
            val text = "二维码优化 🔐\nhttps://example.com/?a=1&b=2"
            val matrix = QrCodec.matrix(text, 512, level)
            val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source(matrix))))
            assertEquals(text, read.text)
            assertEquals(level.name, read.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
            for (i in 0 until matrix.width) { assertFalse(matrix[i, 0]); assertFalse(matrix[0, i]) }
        }
    }

    @Test fun pureQrFallbackReadsDenseImagesButRejectsBlankAndOneDimensionalCodes() {
        val text = "https://example.com/?payload=" + "0123456789abcDEF%20".repeat(75)
        val image = source(QrCodec.matrix(text, 1024))
        assertEquals(text, CodeDecoder.decodePureQr(image)?.text)
        assertEquals(text, CodeDecoder.decode(image)?.text)
        assertEquals(text, CodeDecoder.decode(image.invert())?.text)
        assertNull(CodeDecoder.decodePureQr(source(BarcodeCodec.matrix("PURE-128", CodeFormat.CODE128))))
        assertNull(CodeDecoder.decodePureQr(CodeLuminanceSource(100, 100, ByteArray(10000) { 255.toByte() })))
    }

    @Test fun wifiEscapesSpecialCharactersPreservesPasswordAndSupportsHiddenAndOpenNetworks() {
        val ssid = "网络\\\";, :"
        val password = "  a\\b;,:\"  "
        val value = QrContent.wifi(ssid, password, "WPA", true)
        val parsed = CodeResult(value, BarcodeFormat.QR_CODE).parsed as WifiParsedResult
        assertEquals(ssid, parsed.ssid); assertEquals(password, parsed.password); assertTrue(parsed.isHidden)
        val open = QrContent.wifi("Guest", "ignore me", "nopass", false)
        assertFalse(open.contains("P:")); assertFalse(open.contains("ignore me"))
        assertEquals("nopass", (CodeResult(open, BarcodeFormat.QR_CODE).parsed as WifiParsedResult).networkEncryption)
        assertThrows(IllegalArgumentException::class.java) { QrContent.wifi("", "", "WPA", false) }
        assertThrows(IllegalArgumentException::class.java) { QrContent.wifi("Home", "", "WPA", false) }
    }

    @Test fun mailAndSmsEncodeReservedCharactersWithoutLosingUnicodeOrLineBreaks() {
        val body = "中文 + & ? %\n第二行"
        val mail = CodeResult(QrContent.email("user+box@example.com", "主题 & +", body), BarcodeFormat.QR_CODE).parsed as EmailAddressParsedResult
        assertEquals("user+box@example.com", mail.tos.single()); assertEquals("主题 & +", mail.subject); assertEquals(body, mail.body)
        val sms = CodeResult(QrContent.sms("+86 13800138000", body), BarcodeFormat.QR_CODE).parsed as SMSParsedResult
        assertEquals("+8613800138000", sms.numbers.single()); assertEquals(body, sms.body)
    }

    @Test fun contactUsesEscapedVcardFieldsAndCanBeParsedByZxing() {
        val value = QrContent.contact("张三, 李;四", "+8613800138000", "a@example.com", "公司\\研发\n第二行")
        val parsed = CodeResult(value, BarcodeFormat.QR_CODE).parsed as AddressBookParsedResult
        assertEquals("张三, 李;四", parsed.names.single())
        assertEquals("+8613800138000", parsed.phoneNumbers.single())
        assertEquals("a@example.com", parsed.emails.single())
        assertEquals("公司\\研发\n第二行", parsed.org)
    }

    @Test fun templateValidationAndResultClassification() {
        assertEquals("https://example.com/a?q=1", QrContent.website(" https://example.com/a?q=1 "))
        for (url in listOf("example.com", "javascript:alert(1)", "https://", "http://a b.com"))
            assertThrows(IllegalArgumentException::class.java) { QrContent.website(url) }
        assertThrows(IllegalArgumentException::class.java) { QrContent.email("bad", "", "") }
        assertThrows(IllegalArgumentException::class.java) { QrContent.sms("abc", "") }
        assertThrows(IllegalArgumentException::class.java) { QrContent.sms("()--", "") }
        assertThrows(IllegalArgumentException::class.java) { QrContent.contact("", "", "", "") }
        assertEquals("网址", CodeResult("https://example.com", BarcodeFormat.QR_CODE).typeLabel)
        assertEquals("商品编号", CodeResult("6901234567892", BarcodeFormat.EAN_13).typeLabel)
        assertEquals("文本", CodeResult("普通内容", BarcodeFormat.QR_CODE).typeLabel)
    }
}
