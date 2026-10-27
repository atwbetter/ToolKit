package com.example.androidtoolbox.core

import android.net.Uri
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OtpAccount(
    val id: String,
    val issuer: String,
    val name: String,
    val secret: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
)

object OtpEngine {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalizeSecret(input: String): String {
        val value = input.uppercase(Locale.ROOT).replace(" ", "").replace("-", "").trimEnd('=')
        require(value.isNotEmpty() && value.all { ALPHABET.contains(it) }) { "密钥必须为 Base32 格式" }
        decodeBase32(value)
        return value
    }

    private fun decodeBase32(secret: String): ByteArray {
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (c in secret) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        require(out.isNotEmpty()) { "密钥太短" }
        return out.toByteArray()
    }

    fun fromUri(raw: String): OtpAccount {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme == "otpauth" && uri.host == "totp") { "只支持 otpauth://totp 二维码" }
        val secret = normalizeSecret(uri.getQueryParameter("secret").orEmpty())
        val label = Uri.decode(uri.path.orEmpty().removePrefix("/"))
        val issuer = uri.getQueryParameter("issuer").orEmpty().ifBlank { label.substringBefore(':', "") }
        val name = label.substringAfter(':', label).ifBlank { issuer.ifBlank { "未命名账号" } }
        val algorithm = uri.getQueryParameter("algorithm")?.uppercase(Locale.ROOT) ?: "SHA1"
        require(algorithm in setOf("SHA1", "SHA256", "SHA512")) { "不支持该验证码算法" }
        val digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
        val period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30
        require(digits in 6..8 && period in 15..120) { "验证码参数无效" }
        return OtpAccount(java.util.UUID.randomUUID().toString(), issuer, name, secret, algorithm, digits, period)
    }

    fun code(account: OtpAccount, timeMillis: Long = System.currentTimeMillis()): String {
        val counter = timeMillis / 1000 / account.period
        val message = ByteArray(8) { i -> (counter ushr ((7 - i) * 8)).toByte() }
        val mac = Mac.getInstance("Hmac${account.algorithm}")
        mac.init(SecretKeySpec(decodeBase32(account.secret), "Hmac${account.algorithm}"))
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val value = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulus = (1 until account.digits).fold(10) { acc, _ -> acc * 10 }
        return (value % modulus).toString().padStart(account.digits, '0')
    }

    fun remaining(account: OtpAccount, timeMillis: Long): Int =
        account.period - ((timeMillis / 1000) % account.period).toInt()
}
