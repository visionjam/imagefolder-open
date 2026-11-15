package com.imagefolder.open.core

/** Helpers for building safe, unique archive paths. */
object BundlePaths {

    fun sanitizeSegment(name: String): String {
        val cleaned = name
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()
        val safe = cleaned.ifEmpty { "未命名" }
        return if (safe == "." || safe == "..") "_$safe" else safe
    }

    fun uniqueDirectoryPath(path: String, used: MutableSet<String>): String {
        var candidate = path
        var index = 2
        while (!used.add(candidate)) {
            val base = path.dropLast(1)
            candidate = "$base ($index)/"
            index++
        }
        return candidate
    }

    fun uniqueFilePath(path: String, used: MutableSet<String>): String {
        var candidate = path
        var index = 2
        while (!used.add(candidate)) {
            val slash = path.lastIndexOf('/')
            val directory = if (slash >= 0) path.substring(0, slash + 1) else ""
            val name = path.substring(slash + 1)
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            candidate = "$directory$base ($index)$extension"
            index++
        }
        return candidate
    }
}
