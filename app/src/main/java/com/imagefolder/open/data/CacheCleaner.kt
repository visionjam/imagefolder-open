package com.imagefolder.open.data

import java.io.File

/**
 * Removes the transient files this app writes into `cacheDir`.
 *
 * Three things land there and all of them can contain hidden payloads:
 *
 * - `shared/`    — the generated carrier image handed to the share sheet
 * - `shared-in/` — copies of files shared *into* this app
 * - `imagefolder-*` — archive scratch files/directories
 *
 * Nothing outside [SESSION_DIRECTORIES] / [OWNED_PREFIXES] is touched, so the
 * platform's own cache stays intact.
 *
 * Plain java.io, so the pruning policy is unit tested on the JVM.
 */
object CacheCleaner {

    /** Archive scratch files older than this are dropped on every launch. */
    const val MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000

    /** Above this total size the oldest files are dropped until it fits. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Session copies younger than this survive a launch, so a share that is
     * still being read by the receiving app cannot be pulled out from under it.
     * Ten minutes is far longer than any share hand-off needs.
     */
    const val SESSION_GRACE_MILLIS = 10L * 60 * 1000

    /** Per-session payload copies: dropped aggressively on startup. */
    val SESSION_DIRECTORIES = listOf("shared", "shared-in")

    /** Directories inside cacheDir that belong to this app. */
    val OWNED_DIRECTORIES = SESSION_DIRECTORIES

    /** File / directory name prefixes created by archive extraction. */
    val OWNED_PREFIXES = listOf("imagefolder-")

    /** Total size in bytes of everything this app owns inside [root]. */
    fun totalBytes(root: File): Long = ownedFiles(root).sumOf { it.length() }

    /**
     * Startup policy.
     *
     * Session copies of payloads are deleted outright once they are older than
     * [sessionGraceMillis]; before this existed a single share could leave a
     * carrier image holding hidden data in the cache forever. Archive scratch
     * files keep the age/size policy in [prune].
     *
     * Returns the number of bytes freed.
     */
    fun pruneOnStartup(
        root: File,
        now: Long,
        sessionGraceMillis: Long = SESSION_GRACE_MILLIS,
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxBytes: Long = MAX_BYTES,
    ): Long {
        var freed = 0L
        for (name in SESSION_DIRECTORIES) {
            val directory = File(root, name)
            if (!directory.isDirectory) continue
            val files = mutableListOf<File>()
            collect(directory, files)
            for (file in files) {
                if (now - file.lastModified() <= sessionGraceMillis) continue
                val length = file.length()
                if (file.delete()) freed += length
            }
        }
        freed += prune(root, now, maxAgeMillis, maxBytes)
        return freed
    }

    /**
     * Deletes stale and oversized owned files. Returns the number of bytes
     * freed. [now] is injected so the policy is testable.
     */
    fun prune(
        root: File,
        now: Long,
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxBytes: Long = MAX_BYTES,
    ): Long {
        val files = ownedFiles(root).filter { it.isFile }
        var freed = 0L

        val survivors = mutableListOf<File>()
        for (file in files) {
            if (now - file.lastModified() > maxAgeMillis) {
                freed += file.length()
                file.delete()
            } else {
                survivors += file
            }
        }

        var remaining = survivors.sumOf { it.length() }
        if (remaining > maxBytes) {
            // Oldest first, so the most recently used payloads stay cached.
            survivors.sortedBy { it.lastModified() }.forEach { file ->
                if (remaining <= maxBytes) return@forEach
                val length = file.length()
                if (file.delete()) {
                    remaining -= length
                    freed += length
                }
            }
        }

        removeEmptyDirectories(root)
        return freed
    }

    /** Deletes every owned file, regardless of age. Returns bytes freed. */
    fun clear(root: File): Long {
        var freed = 0L
        ownedFiles(root).forEach { file ->
            val length = if (file.isFile) file.length() else 0L
            if (file.delete()) freed += length
        }
        removeEmptyDirectories(root)
        return freed
    }

    private fun ownedFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        for (directory in OWNED_DIRECTORIES) {
            val owned = File(root, directory)
            if (owned.isDirectory) collect(owned, out)
        }
        root.listFiles()?.forEach { child ->
            if (OWNED_PREFIXES.any { child.name.startsWith(it) }) {
                if (child.isDirectory) collect(child, out) else out += child
            }
        }
        return out
    }

    private fun collect(directory: File, out: MutableList<File>) {
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) collect(child, out) else out += child
        }
    }

    private fun removeEmptyDirectories(root: File) {
        val candidates = OWNED_DIRECTORIES.map { File(root, it) } +
            (root.listFiles()?.filter { child ->
                child.isDirectory && OWNED_PREFIXES.any { child.name.startsWith(it) }
            } ?: emptyList())
        candidates.forEach { deleteEmptyTree(it) }
    }

    private fun deleteEmptyTree(directory: File): Boolean {
        if (!directory.isDirectory) return false
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteEmptyTree(child)
        }
        return directory.listFiles()?.isEmpty() == true && directory.delete()
    }
}
