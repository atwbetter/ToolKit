package com.example.androidtoolbox

import com.example.androidtoolbox.core.OfflineTools
import com.example.androidtoolbox.core.JsonTools
import com.example.androidtoolbox.core.imageSampleSize
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class OfflineToolsTest {
    @Test fun imageSampleLimitsBothEdgesAndRoundedPixelCount() {
        assertEquals(1, imageSampleSize(2000, 2000, 4096, 4_000_000))
        assertEquals(2, imageSampleSize(2001, 2000, 4096, 4_000_000))
        assertEquals(4, imageSampleSize(8193, 1, 4096, 4_000_000))
    }

    @Test fun formattingRejectsExcessiveExpansionButAllowsCompactMode() {
        val input = "[".repeat(60) + List(20_000) { "0" }.joinToString(",") + "]".repeat(60)
        assertThrows(IllegalArgumentException::class.java) { JsonTools.format(input) }
        assertEquals(input, JsonTools.format(input, compact = true))
    }

    @Test fun jsonPreservesPrecisionAndEscapes() {
        val input = "{\"n\":900719925474099312345,\"s\":\"a\\\"b\\n\",\"a\":[true,null,-1.20e+4]}"
        assertEquals(input, JsonTools.format(JsonTools.format(input), true))
        assertEquals("null", JsonTools.format(" null "))
    }
    @Test fun rejectsInvalidJsonAndDeepNesting() {
        listOf("{x:1}", "[1,]", "{\"a\":1,}", "01", "true false", "\"\\x\"", "[.2]", "{\"a\":NaN}", "", "/* comment */{}", "[1e]", "[1.]",
            "[".repeat(66) + "0" + "]".repeat(66)).forEach { invalid ->
            assertTrue("Should reject: $invalid", runCatching { JsonTools.format(invalid) }.isFailure)
        }
    }
    @Test fun convertsTemperatureStorageAndDecimals() {
        assertEquals("0", OfflineTools.convert("32", "温度", "华氏度", "摄氏度"))
        assertEquals("273.15", OfflineTools.convert("0", "温度", "摄氏度", "开尔文"))
        assertEquals("1024", OfflineTools.convert("1", "存储", "MiB", "KiB"))
        assertEquals("1000", OfflineTools.convert("1", "存储", "MB", "KB"))
        assertEquals("0.0254", OfflineTools.convert("1", "长度", "英寸", "米"))
        assertTrue(runCatching { OfflineTools.convert("-1", "温度", "开尔文", "摄氏度") }.isFailure)
        assertTrue(runCatching { OfflineTools.convert("1e999999999", "长度", "米", "千米") }.isFailure)
    }
    @Test fun passwordsContainEverySelectedClass() {
        repeat(50) {
            val p = OfflineTools.password(20, true, true, true, true)
            assertEquals(20, p.length); assertTrue(p.any(Char::isUpperCase)); assertTrue(p.any(Char::isLowerCase))
            assertTrue(p.any(Char::isDigit)); assertTrue(p.any { !it.isLetterOrDigit() })
        }
        assertTrue(runCatching { OfflineTools.password(20, false, false, false, false) }.isFailure)
    }
    @Test fun strictDatesAndDstAmbiguity() {
        val utc = ZoneId.of("UTC")
        assertEquals("1970-01-01 00:00:00", OfflineTools.timestampToDate("0", false, utc))
        assertEquals("1000", OfflineTools.dateToTimestamp("1970-01-01 00:00:01", true, utc))
        assertTrue(runCatching { OfflineTools.dateToTimestamp("2026-02-30 00:00:00", false, utc) }.isFailure)
        assertTrue(runCatching { OfflineTools.dateToTimestamp("2026-11-01 01:30:00", false, ZoneId.of("America/New_York")) }.isFailure)
    }
}
