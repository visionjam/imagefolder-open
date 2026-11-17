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
 *
 * [folderName] is the visible folder below Pictures/ and Download/. It is
 * resolved from resources by the caller so an English device gets
 * `Pictures/ImageFolder` instead of a Chinese path that looks like mojibake
 * in other apps.
 */
class GallerySaver(
    private val context: Context,
    private val folderName: String,
) {

    /** Legacy flat save, kept for simple files. */
    fun save(bytes: ByteArray, fileName: String, mime: String?): SavedFile =
        saveRelative(bytes, fileName, mime)

    /**
     * Saves [bytes] under `Pictures/<folderName>/<dirs>` for images or
     * `Download/<folderName>/<dirs>` for everything else.
     */
    fun saveRelative(bytes: ByteArray, relativePath: String, mime: String?): SavedFile {
        val segments = relativePath.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.replace('\\', '_').replace('\u0000', '_') }
        val fileName = segments.lastOrNull() ?: "file"
        val subDirectory = segments.dropLast(1).joinToString("/")

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
        val baseDirectory = if (isImage) "Pictures/$folderName" else "Download/$folderName"
        val targetDirectory = if (subDirectory.isEmpty()) {
            baseDirectory
        } else {
            "$baseDirectory/$subDirectory"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirectory)
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

        return SavedFile(uri, queryDisplayName(uri) ?: fileName, targetDirectory)
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
