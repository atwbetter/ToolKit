package com.example.androidtoolbox

import com.example.androidtoolbox.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val id = "b01ab10c-6718-4095-83dc-b7de33438a44"
    private val account = OtpAccount(id, "Example", "test@example.com", "JBSWY3DPEHPK3PXP")
    private val local = LocalSnapshot(listOf(LocalNote(id, "标题", "离线正文", true, 1000)), setOf("NOTES"), listOf(RecentTool("JSON", 2000)))
    private val password = "correct horse battery"

    @Test fun plainAndEncryptedRoundTrip() {
        val ordinary = BackupData(local)
        assertEquals(ordinary, BackupCodec.decode(BackupCodec.encode(ordinary, null), ""))
        val full = BackupData(local, listOf(account))
        val encoded = BackupCodec.encode(full, password)
        assertFalse(encoded.contains(account.secret)); assertFalse(encoded.contains("离线正文"))
        assertEquals(full, BackupCodec.decode(encoded, password))
        assertNotEquals(encoded, BackupCodec.encode(full, password))
    }
    @Test fun refusesPlainSecretsWrongPasswordAndTampering() {
        val full = BackupData(local, listOf(account))
        assertTrue(runCatching { BackupCodec.encode(full, null) }.isFailure)
        val encoded = BackupCodec.encode(full, password)
        assertTrue(runCatching { BackupCodec.decode(encoded, "wrong password") }.isFailure)
        val tampered = JSONObject(encoded).apply {
            val payload = getString("payload"); val at = 50
            put("payload", payload.substring(0, at) + (if (payload[at] == 'A') 'B' else 'A') + payload.substring(at + 1))
        }.toString()
        assertTrue(runCatching { BackupCodec.decode(tampered, password) }.isFailure)
    }
    @Test fun rejectsUnknownVersionAndInvalidRecordsBeforeImport() {
        val text = BackupCodec.encode(BackupData(local), null)
        assertTrue(runCatching { BackupCodec.decode(JSONObject(text).put("schemaVersion", 99).toString(), "") }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(JSONObject(text).put("schemaVersion", 1.5).toString(), "") }.isFailure)
        val invalid = JSONObject(text)
        invalid.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("id", "not-a-uuid")
        assertTrue(runCatching { BackupCodec.decode(invalid.toString(), "") }.isFailure)
    }
    @Test fun mergesAccountsWithoutDuplicatingAndRejectsIdConflict() {
        assertEquals(listOf(account), BackupCodec.mergeAccounts(listOf(account), listOf(account.copy(id = "e4cdf5fc-466e-4d1b-822a-718d06aa97c2"))))
        assertTrue(runCatching { BackupCodec.mergeAccounts(listOf(account), listOf(account.copy(secret = "GEZDGNBV"))) }.isFailure)
    }
}
