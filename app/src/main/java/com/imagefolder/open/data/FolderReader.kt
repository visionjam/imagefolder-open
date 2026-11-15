package com.imagefolder.open.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imagefolder.open.core.BundleEntry
import com.imagefolder.open.core.BundlePaths

data class PickedFolder(
    val uri: Uri,
    val name: String,
    val fileCount: Int,
)

data class FolderScanResult(
    val entries: List<BundleEntry>,
    val failedFiles: Int,
)

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
    ): FolderScanResult {
        val root = DocumentFile.fromTreeUri(context, folder.uri)
            ?: throw IllegalArgumentException("无法读取文件夹")
        val entries = mutableListOf<BundleEntry>()
        var failed = 0

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
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes == null) {
                        failed++
                    } else {
                        val filePath = BundlePaths.uniqueFilePath("$prefix$childName", usedPaths)
                        entries += BundleEntry(filePath, bytes)
                    }
                }
            }
        }
        walk(root, rootPath)
        return FolderScanResult(entries, failed)
    }
}
