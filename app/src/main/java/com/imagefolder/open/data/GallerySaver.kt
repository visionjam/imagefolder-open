package com.imagefolder.open.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

data class SavedFile(
    val uri: Uri,
    val displayName: String,
    val location: String,
)

/**
 * Saves extracted files through MediaStore so they appear in the gallery /
 * Downloads app without any storage permission on Android 10+.
 */
class GallerySaver(private val context: Context) {

    fun save(bytes: ByteArray, fileName: String, mime: String?): SavedFile {
        val resolver = context.contentResolver
        val effectiveMime = mime?.takeIf { it.isNotBlank() }
            ?: UriUtils.guessMime(fileName)
            ?: "application/octet-stream"
        val isImage = effectiveMime.startsWith("image/")
        val collection = if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isImage) "Pictures/图片文件夹" else "Download/图片文件夹"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("无法在媒体库中创建文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("无法写入文件")
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }

        return SavedFile(uri, queryDisplayName(uri) ?: fileName, relativePath)
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else {
                    null
                }
            }
}
