package com.imagefolder.open.data

import java.io.File

/** Pure File-tree walker used by the all-files browser; no Android APIs. */
object ImageFiles {
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun collect(root: File, shouldContinue: () -> Boolean = { true }): List<File> {
        val out = mutableListOf<File>()
        if (!walk(root, out, shouldContinue)) return emptyList()
        return out.sortedBy { it.name.lowercase() }
    }

    private fun walk(
        directory: File,
        out: MutableList<File>,
        shouldContinue: () -> Boolean,
    ): Boolean {
        if (!shouldContinue()) return false
        val children = directory.listFiles() ?: return true
        for (child in children) {
            if (!shouldContinue()) return false
            when {
                child.isDirectory -> {
                    if (!walk(child, out, shouldContinue)) return false
                }
                child.isFile && child.extension.lowercase() in EXTENSIONS -> out += child
            }
        }
        return true
    }
}
