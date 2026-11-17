package com.imagefolder.open.core

/** One row of the extraction result browser. */
data class ResultNode(
    /** Display name; directories keep no trailing slash. */
    val name: String,
    /** Directory path ending in "/" for folders, plain path for files. */
    val path: String,
    val isDirectory: Boolean,
    /**
     * Index into the flat extraction result list, or -1 when the directory was
     * synthesized from a deeper path because the bundle did not store an entry
     * for it.
     */
    val itemIndex: Int,
    /** Direct children (files + folders) for a directory; 0 for a file. */
    val childCount: Int = 0,
)

/** A flat extraction result reduced to what the tree needs; Android free. */
data class ResultEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val itemIndex: Int,
)

/**
 * Turns the flat extraction result list into one folder level at a time.
 *
 * Bundles store every entry as a flat path ("payload/sub/a.txt"), so the
 * results screen used to show every file at once and folder rows did nothing
 * when tapped. This keeps the flat list as the source of truth (indices stay
 * valid for save/unpack) and derives the visible level from it.
 */
object ResultTree {

    /** Normalizes "a/b", "/a/b/" and "" to "", "a/b/" and "". */
    fun normalizeFolder(path: String): String {
        val trimmed = path.trim().trim('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /** Parent of a normalized folder; the root's parent is the root. */
    fun parentFolder(folder: String): String {
        val trimmed = normalizeFolder(folder).trim('/')
        if (trimmed.isEmpty()) return ""
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) "" else trimmed.substring(0, slash + 1)
    }

    /** Name shown for a folder path, e.g. "payload/" -> "payload". */
    fun folderName(folder: String): String = normalizeFolder(folder).trim('/').substringAfterLast('/')

    /**
     * Entries directly inside [folder]: folders first, then files, each sorted
     * case-insensitively by name.
     */
    fun children(entries: List<ResultEntry>, folder: String): List<ResultNode> {
        val current = normalizeFolder(folder)
        val indexByPath = HashMap<String, Int>(entries.size)
        for (entry in entries) {
            indexByPath[normalizePath(entry.relativePath)] = entry.itemIndex
        }

        val childNames = HashMap<String, MutableSet<String>>()
        for (entry in entries) {
            val path = normalizePath(entry.relativePath)
            if (path.isEmpty()) continue
            var parent = ""
            var slash = path.indexOf('/')
            while (slash >= 0) {
                val directory = path.substring(0, slash + 1)
                val child = path.substring(parent.length, slash + 1)
                childNames.getOrPut(parent) { LinkedHashSet() } += child
                parent = directory
                slash = path.indexOf('/', slash + 1)
            }
            val leaf = path.substring(parent.length)
            if (leaf.isNotEmpty()) {
                childNames.getOrPut(parent) { LinkedHashSet() } += leaf
            }
        }

        val visible = childNames[current] ?: return emptyList()
        val nodes = visible.map { child ->
            val path = current + child
            val isDirectory = child.endsWith("/")
            ResultNode(
                name = child.trimEnd('/'),
                path = path,
                isDirectory = isDirectory,
                itemIndex = indexByPath[path] ?: -1,
                childCount = if (isDirectory) childNames[path]?.size ?: 0 else 0,
            )
        }
        return nodes.sortedWith(
            compareByDescending<ResultNode> { it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
    }

    /** Keeps the trailing slash of directory entries; strips leading ones. */
    private fun normalizePath(path: String): String = path.trim().trimStart('/')
}
