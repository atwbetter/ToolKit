package com.example.androidtoolbox.core

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Algorithm(val label: String) {
    AES("AES-256-GCM"), RSA("RSA-OAEP"), BASE64("Base64"), SHA256("SHA-256"), SHA512("SHA-512")
}

object CryptoEngine {
    private const val ITERATIONS = 150_000
    private val random = SecureRandom()
    private val utf8 = StandardCharsets.UTF_8
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(value: String) = Base64.getMimeDecoder().decode(value.trim())

    fun process(algorithm: Algorithm, decrypt: Boolean, input: String, key: String): String {
        require(input.isNotEmpty()) { "请输入内容" }
        return when (algorithm) {
            Algorithm.AES -> if (decrypt) decryptAes(input, key) else encryptAes(input, key)
            Algorithm.RSA -> if (decrypt) decryptRsa(input, key) else encryptRsa(input, key)
            Algorithm.BASE64 -> if (decrypt) String(unb64(input), utf8) else b64(input.toByteArray(utf8))
            Algorithm.SHA256 -> digest(input, "SHA-256")
            Algorithm.SHA512 -> digest(input, "SHA-512")
        }
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        require(password.isNotBlank()) { "请输入口令" }
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun encryptAes(input: String, password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val output = cipher.doFinal(input.toByteArray(utf8))
        return "v1:" + b64(salt + iv + output)
    }

    private fun decryptAes(input: String, password: String): String {
        require(input.startsWith("v1:")) { "密文格式不正确" }
        val bytes = unb64(input.substring(3))
        require(bytes.size >= 44) { "密文长度不正确" }
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes.copyOfRange(28, bytes.size)), utf8)
    }

    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    private fun encryptRsa(input: String, publicPem: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pemBytes(publicPem)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
        return b64(cipher.doFinal(input.toByteArray(utf8)))
    }

    private fun decryptRsa(input: String, privatePem: String): String {
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pemBytes(privatePem)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep)
        return String(cipher.doFinal(unb64(input)), utf8)
    }

    private fun pemBytes(value: String): ByteArray {
        require(value.isNotBlank()) { "请输入 PEM 格式密钥" }
        return unb64(value.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").replace("\\s".toRegex(), ""))
    }

    fun generateRsaPair(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        fun pem(type: String, bytes: ByteArray) = "-----BEGIN $type-----\n" +
            b64(bytes).chunked(64).joinToString("\n") + "\n-----END $type-----"
        return pem("PUBLIC KEY", pair.public.encoded) to pem("PRIVATE KEY", pair.private.encoded)
    }

    private fun digest(input: String, name: String) = MessageDigest.getInstance(name)
        .digest(input.toByteArray(utf8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

