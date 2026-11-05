package com.example.androidtoolbox

import com.example.androidtoolbox.core.CropGrid
import com.example.androidtoolbox.core.CropRegion
import org.junit.Assert.*
import org.junit.Test

class ImageCropGridTest {
    @Test fun gridCoversEveryPixelInAnUnevenOffsetSelectionExactlyOnce() {
        val region = CropRegion(2, 5, 13, 12)
        for (grid in CropGrid.entries) {
            val cells = grid.split(region)
            assertEquals(grid.tileCount, cells.size)
            val coverage = IntArray(region.width * region.height)
            for (cell in cells) {
                assertTrue(cell.left >= region.left && cell.top >= region.top)
                assertTrue(cell.fits(region.right, region.bottom))
                for (y in cell.top until cell.bottom) {
                    for (x in cell.left until cell.right) {
                        coverage[(y - region.top) * region.width + x - region.left]++
                    }
                }
            }
            assertTrue(grid.name, coverage.all { it == 1 })
        }
    }

    @Test fun tilesAreOrderedLeftToRightThenTopToBottom() {
        val region = CropRegion(2, 4, 9, 9)
        assertEquals(listOf(CropRegion(2, 4, 5, 6), CropRegion(5, 4, 9, 6),
            CropRegion(2, 6, 5, 9), CropRegion(5, 6, 9, 9)), CropGrid.FOUR.split(region))
        assertEquals(listOf(CropRegion(2, 4, 4, 9), CropRegion(4, 4, 6, 9), CropRegion(6, 4, 9, 9)),
            CropGrid.HORIZONTAL.split(region))
        assertEquals(listOf(CropRegion(2, 4, 9, 5), CropRegion(2, 5, 9, 7), CropRegion(2, 7, 9, 9)),
            CropGrid.VERTICAL.split(region))
    }

    @Test fun selectionMustContainAtLeastOnePixelPerRowAndColumn() {
        val region = CropRegion(10, 10, 12, 12)
        assertTrue(CropGrid.NINE.split(region).isEmpty())
        assertEquals(4, CropGrid.FOUR.split(region).size)
        assertTrue(CropGrid.HORIZONTAL.split(CropRegion(0, 0, 2, 10)).isEmpty())
        assertFalse(CropRegion(-1, 0, 5, 5).fits(10, 10))
        assertFalse(region.fits(10, 10))
    }
}
