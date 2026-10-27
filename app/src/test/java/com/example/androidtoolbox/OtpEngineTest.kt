package com.example.androidtoolbox

import com.example.androidtoolbox.core.OtpAccount
import com.example.androidtoolbox.core.OtpEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class OtpEngineTest {
    @Test fun rfc6238Sha1VectorAt59Seconds() {
        val account = OtpAccount(
            id = "test", issuer = "RFC", name = "test",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8,
        )
        assertEquals("94287082", OtpEngine.code(account, 59_000L))
        assertEquals(1, OtpEngine.remaining(account, 59_000L))
    }
}
