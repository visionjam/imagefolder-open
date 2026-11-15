package com.imagefolder.open.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imagefolder.open.core.BundleEntry
import com.imagefolder.open.core.BundlePaths
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class PickedFolder(
    val uri: Uri,
    val name: String,
    val fileCount: Int,
)

data class FolderScanResult(
    val entries: List<BundleEntry>,
    val failedFiles: Int,
)

class FolderBudgetExceededException : Exception()

/** Reads a SAF tree and turns it into ZIP entries, preserving directory structure. */
object FolderReader {

    fun describe(context: Context, uri: Uri): PickedFolder {
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("无法读取文件夹")
        var count = 0
        fun countFiles(directory: DocumentFile) {
            for (child in directory.listFiles()) {
                if (child.isDirectory) countFiles(child) else if (child.isFile) count++
            }
        }
        countFiles(root)
        return PickedFolder(uri, root.name ?: "文件夹", count)
    }

    fun collect(
        context: Context,
        folder: PickedFolder,
        usedPaths: MutableSet<String>,
        maxBytes: Long,
    ): FolderScanResult {
        val root = DocumentFile.fromTreeUri(context, folder.uri)
            ?: throw IllegalArgumentException("无法读取文件夹")
        val entries = mutableListOf<BundleEntry>()
        var failed = 0
        var totalBytes = 0L

        val rootName = BundlePaths.sanitizeSegment(folder.name.ifBlank { "文件夹" })
        val rootPath = BundlePaths.uniqueDirectoryPath("$rootName/", usedPaths)
        entries += BundleEntry(rootPath, ByteArray(0))

        fun walk(directory: DocumentFile, prefix: String) {
            val children = directory.listFiles().sortedBy { it.name ?: "" }
            for (child in children) {
                val childName = BundlePaths.sanitizeSegment(child.name ?: continue)
                if (child.isDirectory) {
                    val dirPath = BundlePaths.uniqueDirectoryPath("$prefix$childName/", usedPaths)
                    entries += BundleEntry(dirPath, ByteArray(0))
                    walk(child, dirPath)
                } else if (child.isFile) {
                    val declaredSize = child.length()
                    if (declaredSize > 0 && totalBytes + declaredSize > maxBytes) {
                        throw FolderBudgetExceededException()
                    }
                    val input = runCatching {
                        context.contentResolver.openInputStream(child.uri)
                    }.getOrNull()
                    if (input == null) {
                        failed++
                        continue
                    }
                    val bytes = input.use { readAtMost(it, maxBytes - totalBytes) }
                    if (bytes == null) throw FolderBudgetExceededException()
                    totalBytes += bytes.size
                    val filePath = BundlePaths.uniqueFilePath("$prefix$childName", usedPaths)
                    entries += BundleEntry(filePath, bytes)
                }
            }
        }
        walk(root, rootPath)
        return FolderScanResult(entries, failed)
    }

    /** Reads at most [maxBytes]; returns null when the stream is larger. */
    private fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
        if (maxBytes < 0) return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
