package com.example.androidtoolbox

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.androidtoolbox.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    @Test fun persistsAcrossReopenAndMergesIdempotently() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val id = UUID.randomUUID().toString()
        var store = LocalStore(base, name)
        try {
            store.saveNote(LocalNote(id, "old", "body", false, 100))
            store.toggleFavorite("JSON"); store.visit("TEXT"); store.close()
            store = LocalStore(base, name)
            assertEquals("old", store.snapshot().notes.single().title)
            assertTrue("JSON" in store.snapshot().favorites)
            assertEquals("TEXT", store.snapshot().recent.single().tool)
            val data = LocalSnapshot(listOf(LocalNote(id, "new", "new body", true, 200)), setOf("TIME"), listOf(RecentTool("JSON", 300)))
            store.merge(data, "encrypted-placeholder"); store.merge(data, null)
            assertEquals(1, store.snapshot().notes.size)
            assertEquals("new", store.snapshot().notes.single().title)
            store.merge(LocalSnapshot(notes = listOf(LocalNote(id, "older", "", false, 50))), null)
            assertEquals("new", store.snapshot().notes.single().title)
            assertEquals("encrypted-placeholder", store.vault())
            store.writableDatabase.execSQL("CREATE TRIGGER reject_note BEFORE INSERT ON notes WHEN NEW.title='reject' BEGIN SELECT RAISE(ABORT,'test rollback'); END")
            val partial = LocalSnapshot(notes = listOf(LocalNote(UUID.randomUUID().toString(), "first", "", false, 300), LocalNote(UUID.randomUUID().toString(), "reject", "", false, 300)))
            assertTrue(runCatching { store.merge(partial, "should-not-commit") }.isFailure)
            assertEquals(1, store.snapshot().notes.size)
            assertEquals("encrypted-placeholder", store.vault())
            store.deleteNote(id); store.clearRecent()
            assertTrue(store.snapshot().notes.isEmpty()); assertTrue(store.snapshot().recent.isEmpty())
        } finally { store.close(); base.deleteDatabase(name) }
    }
    @Test fun qrImageRoundTripWorksWithoutNetwork() {
        val text = "离线二维码 ToolKit 0.2"
        val bitmap = QrCodec.generate(text)
        try { assertEquals(text, QrCodec.decode(bitmap)) } finally { bitmap.recycle() }
    }
    @Test fun migratesLegacyOtpOnlyAfterEncryptedDataIsCommitted() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}"
        val local = LocalStore(base, "$name.db")
        val store = OtpStore(base, local, name, name)
        val prefs = base.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        try {
            val accounts = listOf(OtpAccount(UUID.randomUUID().toString(), "Example", "local@example.com", "JBSWY3DPEHPK3PXP"))
            val encrypted = store.encode(accounts)
            prefs.edit().putString("accounts", encrypted).commit()
            assertEquals(accounts, store.load())
            assertNull(prefs.getString("accounts", null)); assertEquals(encrypted, local.vault())
            assertFalse(local.vault()!!.contains(accounts.single().secret))
            assertEquals(accounts, store.load())
            local.saveVault("broken")
            assertTrue(runCatching { store.load() }.isFailure)
            assertEquals("broken", local.vault())
        } finally {
            local.close(); base.deleteDatabase("$name.db"); base.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }
}
