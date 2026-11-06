package com.example.androidtoolbox.core

import java.io.File

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Prune only app-owned temporary files, as part of a foreground operation. */
internal fun pruneTemporaryFiles(
    directory: File,
    prefix: String,
    maxEntries: Int,
    maxBytes: Long,
    protectedNames: Set<String> = emptySet(),
) {
    val cutoff = System.currentTimeMillis() - DAY_MS
    val files = directory.listFiles { file -> file.isFile && file.name.startsWith(prefix) }
        ?.sortedByDescending(File::lastModified) ?: return
    var entries = files.count { it.name in protectedNames }
    var bytes = files.filter { it.name in protectedNames }.sumOf(File::length)
    for (file in files) {
        if (file.name in protectedNames) continue
        val size = file.length()
        if (file.lastModified() < cutoff || entries >= maxEntries || size > maxBytes - bytes) {
            if (file.delete()) continue
        }
        entries++
        bytes += size
    }
}
