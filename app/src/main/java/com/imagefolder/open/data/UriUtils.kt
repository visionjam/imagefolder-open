package com.imagefolder.open.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.IOException

data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String?,
)

object UriUtils {

    fun describe(context: Context, uri: Uri): PickedFile {
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var name = fallbackName
        var size = -1L

        fun readColumns(nameColumn: String, sizeColumn: String) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(nameColumn, sizeColumn),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(nameColumn)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(sizeColumn)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
        }

        // SAF providers use OpenableColumns; MediaStore rejects those aliases.
        readColumns(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        if (name == fallbackName || size < 0) {
            readColumns(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        }

        val type = context.contentResolver.getType(uri)
        val guessed = guessMime(name)
        val mime = when {
            type != null && type != "application/octet-stream" -> type
            guessed != null -> guessed
            else -> type
        }
        return PickedFile(uri, name, size, mime)
    }

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取文件")

    /** Reads at most [maxBytes]; returns null when the stream is larger. */
    fun readAtMost(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        return null
    }

    fun guessMime(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}
