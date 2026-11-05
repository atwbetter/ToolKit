package com.example.androidtoolbox

import com.example.androidtoolbox.core.BorderPreset
import com.example.androidtoolbox.core.BorderStyle
import com.example.androidtoolbox.core.ImageBorder
import com.example.androidtoolbox.core.photoExifLines
import org.junit.Assert.*
import org.junit.Test

class ImageBorderTest {
    @Test fun borderExpandsCanvasWithoutChangingPhotoDimensions() {
        val style = BorderStyle(thickness = 10, caption = "湖边")
        val uniform = ImageBorder.layout(600, 400, style, emptyMap())
        val caption = ImageBorder.layout(400, 600, style.copy(preset = BorderPreset.CAPTION), emptyMap())
        assertEquals(680L to 480L, uniform.width to uniform.height)
        assertEquals(600 to 400, uniform.photoWidth to uniform.photoHeight)
        assertEquals(480L to 720L, caption.width to caption.height)
        assertEquals(listOf("湖边"), caption.lines)
        assertTrue(uniform.lines.isEmpty())
    }

    @Test fun exifUsesOnlyAvailableCameraDataAndFormatsRationalValues() {
        val tags = mapOf(
            "厂商" to "SONY", "型号" to "Sony ILCE-7M4",
            "拍摄时间" to "2026:11:05 14:23:00", "焦距（mm）" to "35/1",
            "光圈" to "28/10", "曝光（秒）" to "0.008", "ISO" to "200",
            "GPS" to "31.2, 121.5",
        )
        val style = BorderStyle(preset = BorderPreset.EXIF, caption = "午后")
        val layout = ImageBorder.layout(1400, 900, style, tags)
        assertEquals(listOf("午后", "Sony ILCE-7M4", "35 mm  ·  f/2.8  ·  1/125 s  ·  ISO 200", "2026-11-05 14:23:00"), layout.lines)
        assertEquals(listOf("午后"), ImageBorder.layout(1400, 900, style, emptyMap()).lines)
        assertTrue(ImageBorder.layout(4032, 3024, style, tags).canExport)
        assertTrue(photoExifLines(mapOf("GPS" to "31.2, 121.5", "ISO" to "1/0", "光圈" to "invalid")).isEmpty())
    }

    @Test fun validatesColorsAndFinalCanvasLimits() {
        assertEquals(0xff1b2c3d.toInt(), BorderStyle(color = "#1b2c3d").argb)
        assertNull(BorderStyle(color = "10000-").argb)
        assertNull(BorderStyle(color = "#80000000").argb)
        assertFalse(ImageBorder.layout(8192, 1000, BorderStyle(), emptyMap()).canExport)
        assertFalse(ImageBorder.layout(4000, 4000, BorderStyle(), emptyMap()).canExport)
        assertTrue(ImageBorder.layout(4032, 3024, BorderStyle(thickness = 1), emptyMap()).canExport)
    }
}
