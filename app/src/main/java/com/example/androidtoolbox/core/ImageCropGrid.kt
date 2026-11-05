package com.example.androidtoolbox.core

import java.io.Serializable

internal data class CropRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    fun fits(imageWidth: Int, imageHeight: Int) =
        left >= 0 && top >= 0 && right > left && bottom > top && right <= imageWidth && bottom <= imageHeight
}

internal enum class CropGrid(val label: String, val columns: Int, val rows: Int) {
    FREE("自由裁剪", 1, 1),
    FOUR("四宫格", 2, 2),
    NINE("九宫格", 3, 3),
    HORIZONTAL("横向三格", 3, 1),
    VERTICAL("纵向三格", 1, 3);

    val tileCount = columns * rows

    fun split(region: CropRegion): List<CropRegion> {
        if (region.width < columns || region.height < rows) return emptyList()
        // Use pixel boundaries so every source pixel belongs to exactly one tile.
        fun x(column: Int) = region.left + (region.width.toLong() * column / columns).toInt()
        fun y(row: Int) = region.top + (region.height.toLong() * row / rows).toInt()
        return List(tileCount) { index ->
            val row = index / columns
            val column = index % columns
            CropRegion(x(column), y(row), x(column + 1), y(row + 1))
        }
    }
}

internal data class CropMode(val grid: CropGrid = CropGrid.FREE, val tile: Int? = null) : Serializable
