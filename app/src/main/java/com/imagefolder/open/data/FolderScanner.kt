package com.imagefolder.open.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imagefolder.open.core.CarrierFormat
import com.imagefolder.open.core.CarrierProbe

data class ScanHit(
    val uri: Uri,
    val name: String,
    val size: Long,
    val format: CarrierFormat,
    val entries: Int,
    val encrypted: Boolean,
)

/** Recursively finds images in a SAF tree and probes them without loading payloads. */
object FolderScanner {
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (scanned: Int, total: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ): List<ScanHit> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val images = mutableListOf<DocumentFile>()
        collectImages(root, images)
        images.sortBy { it.name?.lowercase() ?: "" }

        val hits = mutableListOf<ScanHit>()
        val total = images.size
        for ((index, file) in images.withIndex()) {
            if (!shouldContinue()) break
            onProgress(index, total)
            val uri = file.uri
            val probe = runCatching {
                context.contentResolver.openInputStream(uri)?.use { CarrierProbe.probe(it) }
            }.getOrNull() ?: continue
            hits += ScanHit(
                uri = uri,
                name = file.name ?: uri.lastPathSegment ?: "image",
                size = file.length(),
                format = probe.format,
                entries = probe.entries,
                encrypted = probe.encrypted,
            )
        }
        onProgress(total, total)
        return hits
    }

    private fun collectImages(directory: DocumentFile, out: MutableList<DocumentFile>) {
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectImages(file, out)
                isImage(file) -> out += file
            }
        }
    }

    private fun isImage(file: DocumentFile): Boolean {
        val type = file.type?.lowercase()
        if (type == "image/png" || type == "image/jpeg" || type == "image/webp") return true
        val extension = file.name?.substringAfterLast('.', "")?.lowercase()
        return extension in IMAGE_EXTENSIONS
    }
}
