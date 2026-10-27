package com.example.androidtoolbox.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted local storage. Android backup is disabled to avoid restoring data without its Keystore key. */
class OtpStore(context: Context) {
    private val prefs = context.getSharedPreferences("otp_v1", Context.MODE_PRIVATE)
    private val alias = "toolbox_otp_key_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun load(): List<OtpAccount> {
        val encoded = prefs.getString("accounts", null) ?: return emptyList()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 28) { "本地账号数据损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            OtpAccount(
                obj.getString("id"), obj.getString("issuer"), obj.getString("name"),
                obj.getString("secret"), obj.optString("algorithm", "SHA1"),
                obj.optInt("digits", 6), obj.optInt("period", 30)
            )
        }
    }

    fun save(accounts: List<OtpAccount>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(JSONObject().apply {
                put("id", account.id)
                put("issuer", account.issuer)
                put("name", account.name)
                put("secret", account.secret)
                put("algorithm", account.algorithm)
                put("digits", account.digits)
                put("period", account.period)
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("accounts", encoded).commit()) { "保存账号失败" }
    }
}
