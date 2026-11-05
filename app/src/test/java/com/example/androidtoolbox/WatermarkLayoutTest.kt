package com.example.androidtoolbox

import com.example.androidtoolbox.core.watermarkGrid
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class WatermarkLayoutTest {
    @Test fun rotatedGridExtendsPastAllFourImageCorners() {
        for ((width, height) in listOf(400 to 260, 260 to 400, 8192 to 1)) {
            for (angle in listOf(-90, -45, 0, 30, 90)) {
                val grid = watermarkGrid(width, height, 30f, 12f, angle, 5)
                val radians = Math.toRadians(-angle.toDouble())
                for (x in listOf(-width / 2.0, width / 2.0)) {
                    for (y in listOf(-height / 2.0, height / 2.0)) {
                        val rotatedX = x * cos(radians) - y * sin(radians)
                        val rotatedY = x * sin(radians) + y * cos(radians)
                        assertTrue(abs(rotatedX) <= (grid.columns - .5) * grid.stepX)
                        assertTrue(abs(rotatedY) <= grid.rows * grid.stepY)
                    }
                }
                assertTrue((2 * grid.rows + 1) * (2 * grid.columns + 1) <= 3_000)
            }
        }
    }

    @Test fun widerSpacingReducesStampDensity() {
        val dense = watermarkGrid(1200, 900, 180f, 65f, -30, 5)
        val loose = watermarkGrid(1200, 900, 180f, 65f, -30, 30)
        assertTrue(dense.stepX < loose.stepX && dense.stepY < loose.stepY)
        assertTrue(dense.columns * dense.rows > loose.columns * loose.rows)
    }
}
