package com.imagefolder.open.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imagefolder.open.ExtractItem
import java.io.IOException

data class ExportResult(
    val fileCount: Int,
    val failed: Int,
)

/** Writes extracted entries into a user-picked SAF tree, preserving relative paths. */
object FolderExporter {

    fun export(context: Context, treeUri: Uri, items: List<ExtractItem>): ExportResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法访问目标文件夹")
        var files = 0
        var failed = 0
        val directoryCache = mutableMapOf<String, DocumentFile>()

        fun ensureDirectory(segments: List<String>): DocumentFile {
            var current = root
            val keyParts = mutableListOf<String>()
            for (segment in segments) {
                keyParts += segment
                val key = keyParts.joinToString("/")
                val parent = current
                current = directoryCache[key] ?: run {
                    val existing = parent.findFile(segment)
                    val target = if (existing != null && existing.isDirectory) {
                        existing
                    } else {
                        existing?.delete()
                        parent.createDirectory(segment) ?: throw IOException("无法创建目录：$segment")
                    }
                    directoryCache[key] = target
                    target
                }
            }
            return current
        }

        for (item in items) {
            val segments = item.relativePath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            try {
                if (item.isDirectory) {
                    ensureDirectory(segments)
                    continue
                }
                val parent = ensureDirectory(segments.dropLast(1))
                val fileName = segments.last()
                parent.findFile(fileName)?.delete()
                val mime = item.mime ?: "application/octet-stream"
                val document = parent.createFile(mime, fileName)
                    ?: throw IOException("无法创建文件：$fileName")
                context.contentResolver.openOutputStream(document.uri)?.use { it.write(item.bytes) }
                    ?: throw IOException("无法写入文件：$fileName")
                files++
            } catch (e: Exception) {
                failed++
            }
        }
        return ExportResult(files, failed)
    }
}
