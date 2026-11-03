package com.example.androidtoolbox.core

data class LocalNote(
    val id: String,
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RecentTool(val tool: String, val visitedAt: Long)

data class LocalSnapshot(
    val notes: List<LocalNote> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val recent: List<RecentTool> = emptyList(),
)

// Stable IDs are persisted and exported; never persist translated display labels.
val TOOL_IDS = setOf("QR", "CRYPTO", "OTP", "NOTES", "JSON", "TEXT", "UNITS", "PASSWORD", "TIME")
