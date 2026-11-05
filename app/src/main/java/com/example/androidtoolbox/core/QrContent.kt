package com.example.androidtoolbox.core

import java.net.URI
import java.net.URLEncoder

object QrContent {
    fun website(value: String): String {
        val text = value.trim()
        val uri = runCatching { URI(text) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) {
            "请输入完整网址，例如 https://example.com"
        }
        return text
    }

    fun wifi(name: String, password: String, security: String, hidden: Boolean): String {
        require(name.isNotBlank()) { "请输入 Wi-Fi 网络名称" }
        require(security in listOf("WPA", "WEP", "nopass")) { "不支持的 Wi-Fi 加密方式" }
        require(security == "nopass" || password.isNotEmpty()) { "请输入 Wi-Fi 密码，或选择无密码网络" }
        fun escape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(":", "\\:")
            .replace(",", "\\,").replace("\"", "\\\"")
        return "WIFI:T:$security;S:${escape(name)};" +
            (if (security == "nopass") "" else "P:${escape(password)};") + "H:$hidden;;"
    }

    fun email(address: String, subject: String, body: String): String {
        require(address.trim().matches(Regex("[^\\s@,;?]+@[^\\s@,;?]+\\.[^\\s@,;?]+"))) { "请输入有效的收件邮箱" }
        return "mailto:${encode(address.trim())}?subject=${encode(subject)}&body=${encode(body)}"
    }

    fun sms(number: String, body: String): String {
        val phone = number.trim()
        require(phone.matches(Regex("\\+?[0-9 ()-]{3,30}")) && phone.count { it in '0'..'9' } >= 3) { "请输入有效的手机号码" }
        return "sms:${phone.replace(" ", "")}?body=${encode(body)}"
    }

    fun contact(name: String, phone: String, email: String, company: String): String {
        require(name.isNotBlank()) { "请输入联系人姓名" }
        fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
            .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
        return listOf("BEGIN:VCARD", "VERSION:3.0", "N:${escape(name)};;;;", "FN:${escape(name)}",
            "TEL:${escape(phone)}", "EMAIL:${escape(email)}", "ORG:${escape(company)}", "END:VCARD").joinToString("\r\n")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
