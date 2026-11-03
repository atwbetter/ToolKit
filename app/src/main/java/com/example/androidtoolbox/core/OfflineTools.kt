package com.example.androidtoolbox.core

import java.math.BigDecimal
import java.math.MathContext
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object OfflineTools {
    private val random = SecureRandom()
    val units = linkedMapOf(
        "长度" to listOf("米", "千米", "厘米", "毫米", "英寸", "英尺"),
        "质量" to listOf("千克", "克", "毫克", "磅"),
        "温度" to listOf("摄氏度", "华氏度", "开尔文"),
        "存储" to listOf("B", "KB", "MB", "GB", "KiB", "MiB", "GiB"),
    )
    private val factors = mapOf(
        "米" to "1", "千米" to "1000", "厘米" to "0.01", "毫米" to "0.001", "英寸" to "0.0254", "英尺" to "0.3048",
        "千克" to "1", "克" to "0.001", "毫克" to "0.000001", "磅" to "0.45359237",
        "B" to "1", "KB" to "1000", "MB" to "1000000", "GB" to "1000000000",
        "KiB" to "1024", "MiB" to "1048576", "GiB" to "1073741824",
    )
    fun convert(input: String, category: String, from: String, to: String): String {
        require(from in units[category].orEmpty() && to in units[category].orEmpty()) { "请选择同一类别的单位" }
        require(input.length <= 100) { "数值过长" }
        val value = input.trim().toBigDecimalOrNull() ?: error("请输入有效数字")
        require(kotlin.math.abs(value.scale()) <= 100 && value.precision() <= 100) { "数值超出范围" }
        val mc = MathContext.DECIMAL128
        val result = if (category == "温度") {
            val celsius = when (from) {
                "华氏度" -> value.subtract(BigDecimal(32)).multiply(BigDecimal(5)).divide(BigDecimal(9), mc)
                "开尔文" -> value.subtract(BigDecimal("273.15"))
                else -> value
            }
            require(celsius >= BigDecimal("-273.15")) { "温度不能低于绝对零度" }
            when (to) {
                "华氏度" -> celsius.multiply(BigDecimal(9)).divide(BigDecimal(5), mc).add(BigDecimal(32))
                "开尔文" -> celsius.add(BigDecimal("273.15"))
                else -> celsius
            }
        } else value.multiply(BigDecimal(factors.getValue(from)), mc).divide(BigDecimal(factors.getValue(to)), mc)
        return result.round(MathContext(14)).stripTrailingZeros().toPlainString()
    }

    fun password(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
        require(length in 8..128) { "密码长度须为 8–128 位" }
        val pools = buildList {
            if (upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (lower) add("abcdefghijklmnopqrstuvwxyz")
            if (digits) add("0123456789")
            if (symbols) add("!@#\u0024%&*+-_=?:")
        }
        require(pools.isNotEmpty()) { "请至少选择一类字符" }
        val all = pools.joinToString("")
        val result = pools.map { it[random.nextInt(it.length)] }.toMutableList()
        repeat(length - result.size) { result.add(all[random.nextInt(all.length)]) }
        for (i in result.lastIndex downTo 1) {
            val j = random.nextInt(i + 1); val tmp = result[i]; result[i] = result[j]; result[j] = tmp
        }
        return result.joinToString("")
    }

    fun text(input: String, mode: String): String = when (mode) {
        "行去重" -> input.lines().distinct().joinToString("\n")
        "移除空行" -> input.lines().filter { it.isNotBlank() }.joinToString("\n")
        "行排序" -> input.lines().sorted().joinToString("\n")
        "转大写" -> input.uppercase(Locale.ROOT)
        "转小写" -> input.lowercase(Locale.ROOT)
        else -> error("未知操作")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    fun timestampToDate(value: String, millis: Boolean, zone: ZoneId): String {
        val number = value.trim().toLongOrNull() ?: error("请输入有效整数时间戳")
        val instant = if (millis) Instant.ofEpochMilli(number) else Instant.ofEpochSecond(number)
        return dateFormat.withZone(zone).format(instant)
    }
    fun dateToTimestamp(value: String, millis: Boolean, zone: ZoneId): String {
        val date = LocalDateTime.parse(value.trim(), dateFormat)
        val offsets = zone.rules.getValidOffsets(date)
        require(offsets.size == 1) { "该时间处于夏令时切换区间，请改用 UTC 时间" }
        val instant = date.toInstant(offsets.single())
        return (if (millis) instant.toEpochMilli() else instant.epochSecond).toString()
    }
}
