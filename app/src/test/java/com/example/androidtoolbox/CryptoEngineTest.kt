package com.example.androidtoolbox

import com.example.androidtoolbox.core.Algorithm
import com.example.androidtoolbox.core.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CryptoEngineTest {
    @Test fun aesRoundTripUsesFreshNonce() {
        val first = CryptoEngine.process(Algorithm.AES, false, "中文 secret", "long passphrase")
        val second = CryptoEngine.process(Algorithm.AES, false, "中文 secret", "long passphrase")
        assertNotEquals(first, second)
        assertEquals("中文 secret", CryptoEngine.process(Algorithm.AES, true, first, "long passphrase"))
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

