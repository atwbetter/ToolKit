package com.example.androidtoolbox.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** All callers use the serialized IO queue in MainActivity. No destructive upgrade fallback. */
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
        val db = readableDatabase
        val notes = db.rawQuery("SELECT id,title,body,pinned,updated_at FROM notes ORDER BY pinned DESC,updated_at DESC,id", null).use { cur ->
            buildList { while (cur.moveToNext()) add(LocalNote(cur.getString(0), cur.getString(1), cur.getString(2), cur.getInt(3) == 1, cur.getLong(4))) }
        }
        val favorites = db.rawQuery("SELECT tool_id FROM favorites", null).use { cur ->
            buildSet { while (cur.moveToNext()) if (cur.getString(0) in TOOL_IDS) add(cur.getString(0)) }
        }
        val recent = db.rawQuery("SELECT tool_id,visited_at FROM recent_tools ORDER BY visited_at DESC LIMIT 30", null).use { cur ->
            buildList { while (cur.moveToNext()) if (cur.getString(0) in TOOL_IDS) add(RecentTool(cur.getString(0), cur.getLong(1))) }
        }
        return LocalSnapshot(notes, favorites, recent)
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
            val existing = snapshot()
            val byId = existing.notes.associateBy { it.id }
            incoming.notes.forEach { note ->
                if ((byId[note.id]?.updatedAt ?: -1) < note.updatedAt) upsertNote(db, note)
            }
            incoming.favorites.forEach { db.execSQL("INSERT OR IGNORE INTO favorites(tool_id) VALUES(?)", arrayOf(it)) }
            incoming.recent.forEach { item ->
                val prior = existing.recent.find { it.tool == item.tool }?.visitedAt ?: -1
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
