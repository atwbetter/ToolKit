package com.example.androidtoolbox.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BackupData(val local: LocalSnapshot, val accounts: List<OtpAccount> = emptyList())

object BackupCodec {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "toolkit-backup"

    fun encode(data: BackupData, password: String?): String {
        require(data.local.notes.size <= 5000 && data.accounts.size <= 1000) { "单份备份最多 5000 条便签和 1000 个验证器账号" }
        require(password != null || data.accounts.isEmpty()) { "2FA 账号只能导出到加密备份" }
        if (password != null) require(password.isNotBlank() && password.length >= 12) { "备份口令至少 12 个字符，且不能全为空格" }
        val accountArray = accountsJson(data.accounts)
        parseAccounts(accountArray)
        val payload = JSONObject().apply {
            put("notes", JSONArray().apply { data.local.notes.forEach { n -> put(JSONObject().apply {
                put("id", n.id); put("title", n.title); put("body", n.body); put("pinned", n.pinned); put("updatedAt", n.updatedAt)
            }) } })
            put("favorites", JSONArray(data.local.favorites.sorted()))
            put("recent", JSONArray().apply { data.local.recent.forEach { r -> put(JSONObject().apply { put("tool", r.tool); put("visitedAt", r.visitedAt) }) } })
            put("accounts", accountArray)
        }.toString()
        val output = JSONObject().apply {
            put("format", FORMAT); put("schemaVersion", 1); put("appVersion", "0.2.0")
            put("exportedAt", System.currentTimeMillis()); put("encrypted", password != null)
            if (password == null) put("data", JSONObject(payload)) else {
                put("cipher", "AES-256-GCM/PBKDF2-SHA256-150000-v1")
                put("payload", CryptoEngine.process(Algorithm.AES, false, payload, password))
            }
        }.toString(2)
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "备份超过 8 MiB，请减少便签内容后重试" }
        return output
    }

    fun decode(input: String, password: String): BackupData {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "备份文件不能超过 8 MiB" }
        val envelope = JSONObject(JsonTools.format(input, compact = true, maxChars = MAX_FILE_BYTES))
        require(envelope.getString("format") == FORMAT && envelope.get("schemaVersion") == 1) { "不支持此备份格式或版本，请更新应用" }
        val encrypted = envelope.get("encrypted") as? Boolean ?: error("备份加密标志无效")
        val data = if (encrypted) {
            require(envelope.getString("cipher") == "AES-256-GCM/PBKDF2-SHA256-150000-v1") { "不支持此加密备份版本" }
            val json = runCatching { CryptoEngine.process(Algorithm.AES, true, envelope.getString("payload"), password) }
                .getOrElse { error("备份口令错误或文件已损坏") }
            JSONObject(JsonTools.format(json, compact = true, maxChars = MAX_FILE_BYTES))
        } else envelope.getJSONObject("data")
        val notes = data.getJSONArray("notes")
        val favorites = data.getJSONArray("favorites")
        val recent = data.getJSONArray("recent")
        val accounts = data.getJSONArray("accounts")
        require(notes.length() <= 5000 && favorites.length() <= 100 && recent.length() <= 100 && accounts.length() <= 1000) { "备份记录数量超出限制" }
        require(encrypted || accounts.length() == 0) { "拒绝导入未加密的 2FA 密钥" }
        val noteList = (0 until notes.length()).map { i ->
            val n = notes.getJSONObject(i)
            LocalNote(uuid(n.getString("id")), n.getString("title"), n.getString("body"), n.getBoolean("pinned"), timestamp(n.getLong("updatedAt"))).also {
                require(it.title.length <= 200 && it.body.length <= 100_000 && (it.title.isNotBlank() || it.body.isNotBlank())) { "便签内容无效" }
            }
        }
        require(noteList.distinctBy { it.id }.size == noteList.size) { "备份含重复便签 ID" }
        val favoriteSet = (0 until favorites.length()).map { tool(favorites.getString(it)) }.toSet()
        val recentList = (0 until recent.length()).map { i -> recent.getJSONObject(i).let { RecentTool(tool(it.getString("tool")), timestamp(it.getLong("visitedAt"))) } }
            .groupBy { it.tool }.values.map { entries -> entries.maxBy { it.visitedAt } }
        return BackupData(LocalSnapshot(noteList, favoriteSet, recentList), parseAccounts(accounts))
    }

    private fun uuid(value: String): String { require(UUID.fromString(value).toString() == value) { "记录 ID 无效" }; return value }
    private fun tool(value: String): String { require(value in TOOL_IDS) { "备份包含未知工具，请更新应用" }; return value }
    private fun timestamp(value: Long): Long { require(value in 0..253402300799999L) { "记录时间无效" }; return value }

    fun accountsJson(accounts: List<OtpAccount>) = JSONArray().apply {
        accounts.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("issuer", a.issuer); put("name", a.name); put("secret", a.secret)
            put("algorithm", a.algorithm); put("digits", a.digits); put("period", a.period)
        }) }
    }

    fun parseAccounts(array: JSONArray): List<OtpAccount> = (0 until array.length()).map { i ->
        val a = array.getJSONObject(i)
        OtpAccount(uuid(a.getString("id")), a.getString("issuer"), a.getString("name"), OtpEngine.normalizeSecret(a.getString("secret")),
            a.getString("algorithm"), a.getInt("digits"), a.getInt("period")).also {
            require(it.algorithm in setOf("SHA1", "SHA256", "SHA512") && it.digits in 6..8 && it.period in 15..120) { "2FA 参数无效" }
            require(it.issuer.length <= 200 && it.name.length in 1..320 && it.secret.length <= 4096) { "2FA 账号内容过长或为空" }
        }
    }.also { require(it.distinctBy { a -> a.id }.size == it.size) { "备份含重复账号 ID" } }

    /** Keep local account IDs; avoid duplicates when the same secret was manually added on another phone. */
    fun mergeAccounts(local: List<OtpAccount>, incoming: List<OtpAccount>): List<OtpAccount> {
        val merged = local.toMutableList()
        incoming.forEach { a ->
            val sameId = merged.find { it.id == a.id }
            require(sameId == null || sameId == a) { "2FA 账号 ID 冲突，已取消整次导入" }
            if (sameId == null && merged.none { it.secret == a.secret && it.name == a.name && it.issuer == a.issuer && it.algorithm == a.algorithm && it.period == a.period && it.digits == a.digits }) merged.add(a)
        }
        return merged
    }
}
