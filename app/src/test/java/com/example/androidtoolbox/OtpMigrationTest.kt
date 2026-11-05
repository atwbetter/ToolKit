package com.example.androidtoolbox

import com.example.androidtoolbox.core.*
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

class OtpMigrationTest {
    private val secret = "12345678901234567890".toByteArray()
    private fun account(name: String = "alice@example.com", issuer: String = "Google", digits: Int = 6,
        algorithm: String = "SHA1", period: Int = 30) = OtpAccount(UUID.randomUUID().toString(), issuer, name,
        OtpEngine.encodeBase32(secret), algorithm, digits, period)

    private fun proto(write: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply { write(); flush() }
        return bytes.toByteArray()
    }
    private fun uri(bytes: ByteArray) = "otpauth-migration://offline?data=" +
        URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), "UTF-8")
    private fun referenceEntry(type: Int = 2, hash: Int = 1, digits: Int = 1, name: String = "Google:alice@example.com",
        issuer: String = "Google") = proto {
        writeByteArray(1, secret); writeString(2, name); writeString(3, issuer)
        writeEnum(4, hash); writeEnum(5, digits); writeEnum(6, type)
    }
    private fun referencePage(entries: List<ByteArray> = listOf(referenceEntry()), size: Int = 1,
        index: Int = 0, id: Int = 42, version: Int = 1) = uri(proto {
        entries.forEach { writeByteArray(1, it) }; writeInt32(2, version); writeInt32(3, size)
        writeInt32(4, index); writeInt32(5, id)
    })

    @Test fun importsIndependentProtobufWithAlgorithmsUnicodeAndSignedBatchId() {
        for ((hash, algorithm) in listOf(1 to "SHA1", 2 to "SHA256", 3 to "SHA512")) {
            val page = OtpMigration.decode(referencePage(listOf(referenceEntry(hash = hash, digits = 2,
                name = "平台:用户+%测试", issuer = "平台")), id = -12345))
            assertEquals(-12345, page.batchId)
            val parsed = page.accounts.single()
            assertEquals("平台", parsed.issuer); assertEquals("用户+%测试", parsed.name)
            assertEquals(algorithm, parsed.algorithm); assertEquals(8, parsed.digits)
            assertEquals(30, parsed.period); assertArrayEquals(secret, OtpEngine.secretBytes(parsed.secret))
        }
    }

    @Test fun issuerLabelFallbackAndBareNamesArePreserved() {
        val bare = OtpMigration.decode(referencePage(listOf(referenceEntry(name = "alice:personal")))).accounts.single()
        assertEquals("alice:personal", bare.name)
        val fallback = OtpMigration.decode(referencePage(listOf(referenceEntry(issuer = "")))).accounts.single()
        assertEquals("Google", fallback.issuer); assertEquals("alice@example.com", fallback.name)
    }

    @Test fun independentlyDecodesExportedProtobuf() {
        val source = account(digits = 8, algorithm = "SHA512")
        val uri = OtpMigration.encode(listOf(source)).single()
        val input = CodedInputStream.newInstance(Base64.getDecoder().decode(URLDecoder.decode(URI(uri).rawQuery.substringAfter('='), "UTF-8")))
        assertEquals(10, input.readTag())
        val entry = CodedInputStream.newInstance(input.readByteArray())
        assertEquals(10, entry.readTag()); assertArrayEquals(secret, entry.readByteArray())
        assertEquals(18, entry.readTag()); assertEquals(source.name, entry.readStringRequireUtf8())
        assertEquals(26, entry.readTag()); assertEquals(source.issuer, entry.readStringRequireUtf8())
        assertEquals(32, entry.readTag()); assertEquals(3, entry.readEnum())
        assertEquals(40, entry.readTag()); assertEquals(2, entry.readEnum())
        assertEquals(48, entry.readTag()); assertEquals(2, entry.readEnum()); assertTrue(entry.isAtEnd)
        assertEquals(16, input.readTag()); assertEquals(1, input.readInt32())
        assertEquals(24, input.readTag()); assertEquals(1, input.readInt32())
        assertEquals(32, input.readTag()); assertEquals(0, input.readInt32())
        assertEquals(40, input.readTag()); input.readInt32(); assertTrue(input.isAtEnd)
    }

    @Test fun splitExportScansThroughRealQrDecoderAndRestoresSameCodes() {
        val source = (1..23).map { account("账号 $it + %", "平台 $it", if (it % 2 == 0) 8 else 6) }
        val uris = OtpMigration.encode(source)
        assertEquals(3, uris.size)
        var batch = OtpMigrationBatch()
        uris.reversed().forEach { uri ->
            assertTrue(uri.length <= 1600)
            val matrix = QrCodec.matrix(uri, 1024)
            val pixels = IntArray(1024 * 1024) { i -> if (matrix[i % 1024, i / 1024]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val image = RGBLuminanceSource(1024, 1024, pixels)
            // Validate pristine exported modules independently of geometric locator heuristics,
            // then also exercise the same detection/fallback pipeline used by the application.
            val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(image)),
                mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8")).text
            assertEquals(uri, decoded)
            assertEquals(uri, CodeDecoder.decode(image, qrOnly = true)?.text)
            batch = batch.add(OtpMigration.decode(decoded))
        }
        assertTrue(batch.complete); assertEquals(23, batch.accounts.size)
        source.zip(batch.accounts).forEach { (original, restored) ->
            assertEquals(original.copy(id = restored.id), restored)
            assertEquals(OtpEngine.code(original, 59_000), OtpEngine.code(restored, 59_000))
        }
    }

    @Test fun batchesWaitForMissingPagesAndIgnoreRepeatScan() {
        val first = OtpMigration.decode(referencePage(size = 3, index = 0))
        val second = OtpMigration.decode(referencePage(size = 3, index = 1))
        val third = OtpMigration.decode(referencePage(size = 3, index = 2))
        val partial = OtpMigrationBatch().add(third).add(first)
        assertFalse(partial.complete); assertEquals(2, partial.received)
        assertSame(partial, partial.add(OtpMigration.decode(referencePage(size = 3, index = 0))))
        assertTrue(partial.add(second).complete)
    }

    @Test fun mismatchedBatchesAndConflictingPageDoNotChangeStaging() {
        val pending = OtpMigrationBatch().add(OtpMigration.decode(referencePage(size = 2)))
        for (uri in listOf(referencePage(size = 2, index = 1, id = 43), referencePage(size = 3, index = 1),
            referencePage(entries = listOf(referenceEntry(name = "other")), size = 2))) {
            assertThrows(IllegalArgumentException::class.java) { pending.add(OtpMigration.decode(uri)) }
        }
        assertEquals(1, pending.received); assertFalse(pending.complete)
    }

    @Test fun duplicateAccountsMergeIdempotentlyKeepingLocalIds() {
        val existing = account()
        val imported = OtpMigration.decode(referencePage()).accounts
        val merged = BackupCodec.mergeAccounts(listOf(existing), imported + imported.map { it.copy(id = UUID.randomUUID().toString()) })
        assertEquals(listOf(existing), merged)
    }

    @Test fun refusesLossyExportsAndOversizedEntries() {
        for (source in listOf(account(period = 60), account(digits = 7), account(algorithm = "MD5"),
            account().copy(secret = "A".repeat(4000)))) {
            assertThrows(IllegalArgumentException::class.java) { OtpMigration.encode(listOf(source)) }
        }
        assertThrows(IllegalArgumentException::class.java) { OtpMigration.encode(emptyList()) }
    }

    @Test fun rejectsUnsupportedAccountsAndInvalidBatchMetadata() {
        for (uri in listOf(referencePage(listOf(referenceEntry(type = 1))), referencePage(listOf(referenceEntry(hash = 4))),
            referencePage(listOf(referenceEntry(digits = 0))), referencePage(size = 0), referencePage(size = 2, index = 2),
            referencePage(version = 2), referencePage(entries = emptyList()))) {
            assertThrows(Exception::class.java) { OtpMigration.decode(uri) }
        }
    }

    @Test fun rejectsTruncatedInvalidAndUnboundedProtobuf() {
        for (raw in listOf("not a uri", "otpauth://totp/test?secret=ABC", "otpauth-migration://offline?data=!",
            "otpauth-migration://offline?data=AA==&data=AA==", uri(byteArrayOf(10, 127)),
            uri(ByteArray(11) { 0x80.toByte() }), uri(byteArrayOf(0)), "x".repeat(20_000))) {
            assertThrows(Exception::class.java) { OtpMigration.decode(raw) }
        }
    }

    @Test fun skipsUnknownProtobufFields() {
        val payload = proto {
            writeByteArray(1, referenceEntry()); writeInt32(2, 1); writeInt32(3, 1)
            writeFixed64(8, 10); writeFixed32(9, 20); writeString(10, "future"); writeUInt64(11, 99)
        }
        assertEquals(1, OtpMigration.decode(uri(payload)).accounts.size)
    }

    @Test fun nameSearchMatchesIssuerAndAccountWithoutSearchingSecret() {
        val source = listOf(account("Alice@Example.com", "Google"), account("工作账号", "质子邮箱"), account("bob", "GitHub"))
        assertEquals(listOf(source[0]), OtpEngine.search(source, "  gOoG  "))
        assertEquals(listOf(source[0]), OtpEngine.search(source, "EXAMPLE"))
        assertEquals(listOf(source[1]), OtpEngine.search(source, "质子"))
        assertEquals(source, OtpEngine.search(source, "  "))
        assertTrue(OtpEngine.search(source, source[0].secret).isEmpty())
    }
}
