package com.example.androidtoolbox.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Access from IO. Writes and backups are serialized by MainActivity. */
class LocalStore(context: Context, databaseName: String = "toolbox.db") : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(context.applicationContext).also { instance = it }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, pinned INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE favorites(tool_id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE recent_tools(tool_id TEXT PRIMARY KEY, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE vault(key TEXT PRIMARY KEY, ciphertext TEXT NOT NULL)")
        listOf("QR", "NOTES", "OTP").forEach {
            db.execSQL("INSERT INTO favorites(tool_id) VALUES(?)", arrayOf(it))
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("缺少数据库升级路径：$oldVersion → $newVersion，请保留数据并更新应用")
    }

    fun snapshot(): LocalSnapshot {
        val notes = readableDatabase.rawQuery("SELECT id,title,body,pinned,updated_at FROM notes ORDER BY pinned DESC,updated_at DESC,id", null).use { cur ->
            buildList { while (cur.moveToNext()) add(cur.note()) }
        }
        return LocalSnapshot(notes, favorites(), recent())
    }

    internal fun noteCount(): Int = android.database.DatabaseUtils.queryNumEntries(readableDatabase, "notes").toInt()

    internal fun favorites(): Set<String> = readableDatabase.rawQuery("SELECT tool_id FROM favorites", null).use { cur ->
        buildSet { while (cur.moveToNext()) if (cur.getString(0) in TOOL_IDS) add(cur.getString(0)) }
    }

    internal fun recent(): List<RecentTool> =
        readableDatabase.rawQuery("SELECT tool_id,visited_at FROM recent_tools ORDER BY visited_at DESC LIMIT 30", null).use { cur ->
            buildList { while (cur.moveToNext()) if (cur.getString(0) in TOOL_IDS) add(RecentTool(cur.getString(0), cur.getLong(1))) }
        }

    internal fun notePreviews(query: String, pinnedOnly: Boolean, checkCancelled: () -> Unit): List<NotePreview> {
        // SQLite NOCASE is ASCII-only. Read full text one row at a time for the existing Unicode search.
        val body = if (query.isEmpty()) "substr(body,1,240)" else "body"
        val where = if (pinnedOnly) " WHERE pinned=1" else ""
        return readableDatabase.rawQuery("SELECT id,title,$body,pinned,updated_at, length(CAST(body AS BLOB)) > 0 FROM notes$where ORDER BY pinned DESC,updated_at DESC,id", null).use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    checkCancelled()
                    val title = cur.getString(1)
                    val text = cur.getString(2)
                    if (title.contains(query, true) || text.contains(query, true)) {
                        add(NotePreview(cur.getString(0), title, text.take(240), cur.getInt(5) == 1, cur.getInt(3) == 1, cur.getLong(4)))
                    }
                }
            }
        }
    }

    internal fun note(id: String): LocalNote? =
        readableDatabase.rawQuery("SELECT id,title,body,pinned,updated_at FROM notes WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.note() else null
        }

    private fun android.database.Cursor.note() =
        LocalNote(getString(0), getString(1), getString(2), getInt(3) == 1, getLong(4))

    internal fun backupSnapshot(): LocalSnapshot {
        readableDatabase.rawQuery("SELECT COUNT(*), SUM(length(CAST(title AS BLOB)) + length(CAST(body AS BLOB))) FROM notes", null).use {
            check(it.moveToFirst())
            require(it.getLong(0) <= 5000 && it.getLong(1) <= BackupCodec.MAX_FILE_BYTES) {
                "备份最多包含 5000 条便签且不得超过 8 MiB，请减少便签内容后重试"
            }
        }
        return snapshot()
    }

    fun saveNote(note: LocalNote) {
        require(note.title.length <= 200 && note.body.length <= 100_000) { "标题最多 200 字，正文最多 10 万字" }
        require(note.title.isNotBlank() || note.body.isNotBlank()) { "请输入便签内容" }
        upsertNote(writableDatabase, note)
    }

    private fun upsertNote(db: SQLiteDatabase, note: LocalNote) {
        check(db.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", note.id); put("title", note.title); put("body", note.body)
            put("pinned", if (note.pinned) 1 else 0); put("updated_at", note.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存便签失败" }
    }

    fun deleteNote(id: String) { writableDatabase.delete("notes", "id=?", arrayOf(id)) }

    fun toggleFavorite(id: String) {
        require(id in TOOL_IDS)
        transaction { db ->
            if (db.delete("favorites", "tool_id=?", arrayOf(id)) == 0)
                db.execSQL("INSERT INTO favorites(tool_id) VALUES(?)", arrayOf(id))
        }
    }

    fun visit(id: String) {
        require(id in TOOL_IDS)
        writableDatabase.execSQL("INSERT OR REPLACE INTO recent_tools(tool_id,visited_at) VALUES(?,?)", arrayOf<Any>(id, System.currentTimeMillis()))
    }

    fun clearRecent() { writableDatabase.delete("recent_tools", null, null) }

    fun setting(key: String): String? = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun setSetting(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    fun vault(): String? = readableDatabase.rawQuery("SELECT ciphertext FROM vault WHERE key='otp'", null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun saveVault(ciphertext: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO vault(key,ciphertext) VALUES('otp',?)", arrayOf(ciphertext))
    }

    /** Entire merge, including freshly re-encrypted OTP data, commits or rolls back together. */
    fun merge(incoming: LocalSnapshot, otpCiphertext: String?) {
        transaction { db ->
            val byId = db.rawQuery("SELECT id,updated_at FROM notes", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) }
            }
            incoming.notes.forEach { note ->
                if ((byId[note.id] ?: -1) < note.updatedAt) upsertNote(db, note)
            }
            incoming.favorites.forEach { db.execSQL("INSERT OR IGNORE INTO favorites(tool_id) VALUES(?)", arrayOf(it)) }
            val recentByTool = recent().associateBy { it.tool }
            incoming.recent.forEach { item ->
                val prior = recentByTool[item.tool]?.visitedAt ?: -1
                if (item.visitedAt > prior) db.execSQL("INSERT OR REPLACE INTO recent_tools(tool_id,visited_at) VALUES(?,?)", arrayOf<Any>(item.tool, item.visitedAt))
            }
            if (otpCiphertext != null) saveVault(otpCiphertext)
        }
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
