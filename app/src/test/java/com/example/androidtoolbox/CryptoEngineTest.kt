package com.example.androidtoolbox

import com.example.androidtoolbox.core.Algorithm
import com.example.androidtoolbox.core.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CryptoEngineTest {
    private val sampleText = "中文 secret\nAES"
    private val key128 = "0123456789abcdef"
    private val key256 = "0123456789abcdef0123456789abcdef"
    // Independently generated with .NET AES-CBC/PKCS7, IV = 00..0f, raw UTF-8 keys.
    private val fixture128 = "aes-128-cbc:v1:AAECAwQFBgcICQoLDA0OD4H1I/qg4I5EgF/8STJimlN959F5wicJU/TRCH+cGRr+"
    private val fixture256 = "aes-256-cbc:v1:AAECAwQFBgcICQoLDA0OD1o65S79pOQ3orhmfbrWUP0Iz/0BlgmY33cMu8kn95CX"

    @Test fun aesRoundTripUsesFreshNonce() {
        val first = CryptoEngine.process(Algorithm.AES, false, "中文 secret", "long passphrase")
        val second = CryptoEngine.process(Algorithm.AES, false, "中文 secret", "long passphrase")
        assertNotEquals(first, second)
        assertEquals("中文 secret", CryptoEngine.process(Algorithm.AES, true, first, "long passphrase"))
    }

    @Test fun existingGcmV1CiphertextRemainsCompatible() {
        // .NET AES-GCM + PBKDF2-SHA256, 150000 iterations, same v1 layout as 0.2.0.
        val legacy = "v1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG9w+Nmu7IWpTxSY4oIRlK02puVrT2EOVwOOs8IupHdPFgQ=="
        assertEquals(sampleText, CryptoEngine.process(Algorithm.AES, true, legacy, "long passphrase"))
        assertTrue(CryptoEngine.process(Algorithm.AES, false, sampleText, "long passphrase").startsWith("v1:"))
        assertThrows(Exception::class.java) {
            CryptoEngine.process(Algorithm.AES, true, legacy, "wrong passphrase")
        }
    }

    @Test fun aes128DecryptsIndependentRawKeyFixture() {
        assertEquals(sampleText, CryptoEngine.process(Algorithm.AES128, true, fixture128, key128))
    }

    @Test fun aes256DecryptsIndependentRawKeyFixture() {
        assertEquals(sampleText, CryptoEngine.process(Algorithm.AES256, true, fixture256, key256))
    }

    @Test fun cbcRoundTripsUnicodeWhitespaceAndBlockBoundaries() {
        for ((algorithm, key) in listOf(Algorithm.AES128 to key128, Algorithm.AES256 to key256)) {
            for (text in listOf("x", "a".repeat(15), "a".repeat(16), "a".repeat(17), " \n中文 🔐\t ")) {
                val encrypted = CryptoEngine.process(algorithm, false, text, key)
                assertEquals(text, CryptoEngine.process(algorithm, true, encrypted, key))
            }
        }
    }

    @Test fun cbcUsesFreshIvIncludedInCiphertext() {
        for ((algorithm, key) in listOf(Algorithm.AES128 to key128, Algorithm.AES256 to key256)) {
            val first = CryptoEngine.process(algorithm, false, sampleText, key)
            val second = CryptoEngine.process(algorithm, false, sampleText, key)
            val firstBytes = Base64.getDecoder().decode(first.substringAfter(":v1:"))
            val secondBytes = Base64.getDecoder().decode(second.substringAfter(":v1:"))
            assertNotEquals(first, second)
            assertTrue(!firstBytes.copyOfRange(0, 16).contentEquals(secondBytes.copyOfRange(0, 16)))
            assertEquals(0, (firstBytes.size - 16) % 16)
        }
    }

    @Test fun rawKeysUseUtf8ByteLengthAndPreserveSpaces() {
        for ((algorithm, key) in listOf(
            Algorithm.AES128 to "中".repeat(5) + "a",
            Algorithm.AES256 to "中".repeat(10) + "ab",
            Algorithm.AES128 to " 123456789abcde ",
            Algorithm.AES256 to " " + "a".repeat(30) + " ",
        )) {
            val encrypted = CryptoEngine.process(algorithm, false, sampleText, key)
            assertEquals(sampleText, CryptoEngine.process(algorithm, true, encrypted, key))
            if (key.startsWith(" ")) assertThrows(IllegalArgumentException::class.java) {
                CryptoEngine.process(algorithm, true, encrypted, key.trim())
            }
        }
    }

    @Test fun rawKeysRejectWrongLengthForEncryptionAndDecryption() {
        for ((algorithm, fixture) in listOf(Algorithm.AES128 to fixture128, Algorithm.AES256 to fixture256)) {
            val size = algorithm.rawKeyBytes!!
            for (key in listOf("", "a".repeat(size - 1), "a".repeat(size + 1), "中".repeat(size))) {
                for (decrypt in listOf(false, true)) {
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        CryptoEngine.process(algorithm, decrypt, if (decrypt) fixture else sampleText, key)
                    }
                    assertTrue(error.message!!.contains("$size 字节"))
                }
            }
        }
    }

    @Test fun cbcRejectsWrongAlgorithmAndMalformedPayloads() {
        for ((algorithm, key) in listOf(Algorithm.AES128 to key128, Algorithm.AES256 to key256)) {
            val prefix = "aes-${key.toByteArray().size * 8}-cbc:v1:"
            val wrongAlgorithm = if (algorithm == Algorithm.AES128) fixture256 else fixture128
            for (input in listOf(wrongAlgorithm, "v1:AAAA", prefix, prefix + "!not-base64!",
                prefix + Base64.getEncoder().encodeToString(ByteArray(16)),
                prefix + Base64.getEncoder().encodeToString(ByteArray(33)))) {
                assertThrows(IllegalArgumentException::class.java) { CryptoEngine.process(algorithm, true, input, key) }
            }
        }
    }

    @Test fun cbcRejectsWrongKeyAndInvalidPadding() {
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.process(Algorithm.AES128, true, fixture128, "fedcba9876543210")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.process(Algorithm.AES256, true, fixture256, "fedcba9876543210fedcba9876543210")
        }
        // The plaintext spans two blocks; flipping the end of block 1 corrupts block 2's padding.
        for ((algorithm, key, fixture) in listOf(Triple(Algorithm.AES128, key128, fixture128), Triple(Algorithm.AES256, key256, fixture256))) {
            val bytes = Base64.getDecoder().decode(fixture.substringAfter(":v1:"))
            bytes[31] = (bytes[31].toInt() xor 1).toByte()
            val tampered = fixture.substringBefore(":v1:") + ":v1:" + Base64.getEncoder().encodeToString(bytes)
            assertThrows(IllegalArgumentException::class.java) { CryptoEngine.process(algorithm, true, tampered, key) }
        }
    }

    @Test fun rsaRoundTrip() {
        val (publicKey, privateKey) = CryptoEngine.generateRsaPair()
        val cipher = CryptoEngine.process(Algorithm.RSA, false, "test", publicKey)
        assertEquals("test", CryptoEngine.process(Algorithm.RSA, true, cipher, privateKey))
    }

    @Test fun sha256KnownValue() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CryptoEngine.process(Algorithm.SHA256, false, "abc", ""))
    }
}

