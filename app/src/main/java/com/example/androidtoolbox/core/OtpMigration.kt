package com.example.androidtoolbox.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class OtpMigrationPage(
    val accounts: List<OtpAccount>, val batchSize: Int, val batchIndex: Int,
    val batchId: Int, val fingerprint: String,
)

/** Immutable staging: no account is persisted until every page has arrived and the user imports. */
class OtpMigrationBatch(private val pages: Map<Int, OtpMigrationPage> = emptyMap()) {
    val received: Int get() = pages.size
    val total: Int get() = pages.values.firstOrNull()?.batchSize ?: 0
    val complete: Boolean get() = total > 0 && received == total
    val accounts: List<OtpAccount> get() = pages.toSortedMap().values.flatMap { it.accounts }

    fun add(page: OtpMigrationPage): OtpMigrationBatch {
        pages.values.firstOrNull()?.let {
            require(it.batchId == page.batchId && it.batchSize == page.batchSize) { "这张二维码属于其他迁移批次，请先完成或取消当前批次" }
        }
        pages[page.batchIndex]?.let {
            require(it.fingerprint == page.fingerprint) { "同一序号的二维码内容不同，已保留当前批次" }
            return this
        }
        require(accounts.size + page.accounts.size <= 1000) { "一次最多导入 1000 个账号" }
        return OtpMigrationBatch(pages + (page.batchIndex to page))
    }
}

/** Google Authenticator's otpauth-migration v1 protobuf wire format, without a runtime dependency. */
object OtpMigration {
    private const val MAX_URI = 16_384
    private const val MAX_QR_URI = 1600
    private const val PREFIX = "otpauth-migration://offline?data="

    fun decode(raw: String): OtpMigrationPage {
        require(raw.length <= MAX_URI) { "迁移二维码内容过长" }
        val uri = try { URI(raw.trim()) } catch (_: Exception) { error("迁移链接格式无效") }
        require(uri.scheme == "otpauth-migration" && uri.host == "offline" && uri.fragment == null) { "不是 Google Authenticator 迁移二维码" }
        val parameters = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            .filter { it[0] == "data" }
        require(parameters.size == 1 && parameters.single().size == 2) { "迁移二维码缺少或重复 data 参数" }
        val bytes = try {
            // Preserve literal '+' as well as Google's percent-encoded Base64 form.
            Base64.getDecoder().decode(URLDecoder.decode(parameters.single()[1].replace("+", "%2B"), "UTF-8"))
        } catch (_: IllegalArgumentException) { error("迁移二维码编码无效") }
        require(bytes.size in 1..8192) { "迁移二维码大小无效" }
        val reader = Reader(bytes)
        val accounts = mutableListOf<OtpAccount>()
        var version = 0; var size = 1; var index = 0; var id = 0
        while (reader.hasNext()) {
            val tag = reader.tag()
            when (tag) {
                10 -> { require(accounts.size < 1000) { "迁移账号数量过多" }; accounts += decodeAccount(reader.bytes()) }
                16 -> version = reader.int()
                24 -> size = reader.int()
                32 -> index = reader.int()
                40 -> id = reader.varint().toInt()
                else -> reader.skip(tag)
            }
        }
        require(version == 1) { "不支持此 Google 迁移版本：$version" }
        require(size in 1..1000 && index in 0 until size) { "迁移二维码的批次序号无效" }
        require(accounts.isNotEmpty()) { "迁移二维码中没有账号" }
        return OtpMigrationPage(accounts, size, index, id,
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    private fun decodeAccount(bytes: ByteArray): OtpAccount {
        val reader = Reader(bytes)
        var secret = byteArrayOf(); var name = ""; var issuer = ""
        var algorithm = 0; var digits = 0; var type = 0
        while (reader.hasNext()) {
            val tag = reader.tag()
            when (tag) {
                10 -> secret = reader.bytes()
                18 -> name = text(reader.bytes())
                26 -> issuer = text(reader.bytes())
                32 -> algorithm = reader.int()
                40 -> digits = reader.int()
                48 -> type = reader.int()
                else -> reader.skip(tag)
            }
        }
        require(type == 2) { "此批次含 HOTP 或未知账号类型，当前仅支持 TOTP；未导入任何账号" }
        val hash = when (algorithm) { 1 -> "SHA1"; 2 -> "SHA256"; 3 -> "SHA512"; else -> error("迁移账号包含不支持的摘要算法，未导入任何账号") }
        val count = when (digits) { 1 -> 6; 2 -> 8; else -> error("迁移账号的验证码位数无效") }
        require(secret.isNotEmpty() && secret.size <= 2560) { "迁移账号密钥长度无效" }
        if (issuer.isBlank() && ':' in name) issuer = name.substringBefore(':')
        // Google's name may be either a bare account name or an issuer-prefixed label.
        if (issuer.isNotBlank() && name.startsWith("$issuer:")) name = name.removePrefix("$issuer:").trimStart()
        name = name.ifBlank { issuer.ifBlank { "未命名账号" } }
        require(issuer.length <= 200 && name.length <= 320) { "迁移账号名称过长" }
        return OtpAccount(UUID.randomUUID().toString(), issuer, name, OtpEngine.encodeBase32(secret), hash, count)
    }

    fun exportIssue(account: OtpAccount): String? = when {
        account.period != 30 -> "Google 迁移格式仅支持 30 秒周期"
        account.digits !in setOf(6, 8) -> "Google 迁移格式仅支持 6 或 8 位验证码"
        account.algorithm !in setOf("SHA1", "SHA256", "SHA512") -> "不支持此摘要算法"
        else -> null
    }

    fun encode(accounts: List<OtpAccount>): List<String> {
        require(accounts.size in 1..1000) { "请选择 1 至 1000 个账号" }
        val entries = accounts.map { account ->
            require(exportIssue(account) == null) { "${account.issuer.ifBlank { account.name }}：${exportIssue(account)}，请使用口令加密备份" }
            require(account.issuer.length <= 200 && account.name.length in 1..320) { "账号名称长度无效" }
            Writer().apply {
                bytes(1, OtpEngine.secretBytes(OtpEngine.normalizeSecret(account.secret)))
                bytes(2, account.name.toByteArray(Charsets.UTF_8))
                bytes(3, account.issuer.toByteArray(Charsets.UTF_8))
                number(4, when (account.algorithm) { "SHA256" -> 2; "SHA512" -> 3; else -> 1 })
                number(5, if (account.digits == 8) 2 else 1)
                number(6, 2)
            }.finish()
        }
        val batchId = SecureRandom().nextInt(Int.MAX_VALUE)
        fun payload(group: List<ByteArray>, count: Int, index: Int) = PREFIX + URLEncoder.encode(
            Base64.getEncoder().encodeToString(Writer().apply {
                group.forEach { bytes(1, it) }; number(2, 1); number(3, count); number(4, index); number(5, batchId)
            }.finish()), "UTF-8")
        val groups = mutableListOf<List<ByteArray>>()
        var group = emptyList<ByteArray>()
        entries.forEach { entry ->
            // Reserve the largest supported batch metadata when estimating QR capacity.
            require(payload(listOf(entry), 1000, 999).length <= MAX_QR_URI - 64) { "单个账号信息过长，无法生成迁移二维码，请使用口令加密备份" }
            if (group.size >= 10 || payload(group + listOf(entry), 1000, 999).length > MAX_QR_URI - 64) {
                groups += group; group = emptyList()
            }
            group = group + listOf(entry)
        }
        if (group.isNotEmpty()) groups += group
        return groups.mapIndexed { index, items -> payload(items, groups.size, index) }
    }

    private fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { error("迁移账号名称编码无效") }

    private class Writer {
        private val output = ByteArrayOutputStream()
        private fun varint(value: Int) {
            var n = value.toLong() and 0xffffffffL
            while (n >= 128) { output.write((n.toInt() and 127) or 128); n = n ushr 7 }
            output.write(n.toInt())
        }
        fun number(field: Int, value: Int) { varint(field shl 3); varint(value) }
        fun bytes(field: Int, value: ByteArray) { varint((field shl 3) or 2); varint(value.size); output.write(value) }
        fun finish(): ByteArray = output.toByteArray()
    }

    private class Reader(private val input: ByteArray) {
        private var position = 0
        fun hasNext() = position < input.size
        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(hasNext()) { "迁移二维码数据被截断" }
                val byte = input[position++].toInt() and 255
                require(i < 9 || byte <= 1) { "迁移二维码整数溢出" }
                value = value or ((byte and 127).toLong() shl (i * 7))
                if (byte and 128 == 0) return value
            }
            error("迁移二维码整数无效")
        }
        fun int(): Int { val n = varint(); require(n in 0..Int.MAX_VALUE.toLong()) { "迁移二维码参数无效" }; return n.toInt() }
        fun tag(): Int = int().also { require(it ushr 3 > 0) { "迁移二维码字段无效" } }
        fun bytes(): ByteArray {
            val count = int(); require(count <= input.size - position) { "迁移二维码数据被截断" }
            return input.copyOfRange(position, position + count).also { position += count }
        }
        fun skip(tag: Int) {
            val count = when (tag and 7) { 0 -> { varint(); return }; 1 -> 8; 2 -> { bytes(); return }; 5 -> 4; else -> error("迁移二维码字段类型无效") }
            require(count <= input.size - position) { "迁移二维码数据被截断" }; position += count
        }
    }
}
