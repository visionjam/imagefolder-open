package com.imagefolder.open.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.imagefolder.open.ExtractItem
import com.imagefolder.open.GeneratedResult
import com.imagefolder.open.core.MimeTypes
import com.imagefolder.open.data.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes [bytes] into a fresh subdirectory of the FileProvider share cache.
 *
 * Unlike [writeShareCache], which uses the final file name, this keeps two
 * files with the same leaf name (a/x.txt and b/x.txt) from overwriting each
 * other while an external app still has the first one open.
 */
internal fun writeOpenCache(context: Context, bytes: ByteArray, fileName: String): Uri {
    val root = File(context.cacheDir, "shared").apply { mkdirs() }
    val directory = File(root, "open-" + System.nanoTime()).apply { mkdirs() }
    val safeName = fileName.replace('/', '_').replace('\\', '_').ifBlank { "file" }
    val file = File(directory, safeName)
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

internal fun writeShareCache(context: Context, bytes: ByteArray, fileName: String): Uri {
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    val now = System.currentTimeMillis()
    directory.listFiles()?.forEach { old ->
        if (now - old.lastModified() > 24L * 60 * 60 * 1000) old.delete()
    }
    val file = File(directory, fileName.replace('/', '_'))
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * MIME type used when handing an extracted entry to another app.
 *
 * [ExtractItem.mime] is `application/octet-stream` for names Android does not
 * recognise, and ACTION_VIEW with that type resolves to a file manager (or to
 * nothing) instead of a text editor. Fall back to the extension, then to a
 * content sniff, before giving up on a wildcard.
 */
internal fun handoffMime(item: ExtractItem): String {
    val declared = item.mime?.trim().orEmpty()
    if (declared.isNotEmpty() && declared != "application/octet-stream" && declared != "*/*") {
        return declared
    }
    val guessed = UriUtils.guessMime(item.name)
    if (guessed != null && guessed != "application/octet-stream") return guessed
    if (MimeTypes.looksLikeText(item.bytes)) return "text/plain"
    return declared.ifEmpty { "application/octet-stream" }
}

/**
 * File name used for the FileProvider copy handed to another app.
 *
 * The original name is kept when it already has an extension; otherwise the
 * extension is derived from [mime] so receivers that sniff by name
 * (`README` -> `README.txt`) still recognise the file.
 */
internal fun handoffFileName(name: String, mime: String?): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        .replace('\u0000', '_')
        .ifBlank { "file" }
    if (MimeTypes.extensionOf(leaf).isNotEmpty()) return leaf
    val extension = mime
        ?.takeIf { it.isNotBlank() && it != "*/*" }
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: return leaf
    return "$leaf.$extension"
}

/**
 * Shares one or more extracted entries through the system share sheet.
 *
 * Entries that were already saved are shared through their MediaStore URI;
 * unsaved ones get a FileProvider copy first, exactly like "open with".
 * Returns false when [items] contains only directories.
 */
internal suspend fun shareExtractedFiles(
    context: Context,
    items: List<ExtractItem>,
    chooserTitle: CharSequence? = null,
): Boolean {
    val files = items.filterNot { it.isDirectory }
    if (files.isEmpty()) return false
    val handoffs = files.map { item -> item to handoffMime(item) }
    val type = handoffs.map { it.second }.distinct().singleOrNull() ?: "*/*"
    val uris = withContext(Dispatchers.IO) {
        handoffs.map { (item, mime) ->
            item.savedUri ?: writeOpenCache(context, item.bytes, handoffFileName(item.name, mime))
        }
    }

    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uris.first())
            clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
        }
    } else {
        val clip = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, chooserTitle))
    return true
}

/**
 * Shares one image, or every part of a split carrier at once.
 *
 * Split parts are only useful as a set, so a multi-part result is sent with
 * ACTION_SEND_MULTIPLE; the receiving app sees all images in one message.
 */
internal suspend fun shareGeneratedImages(context: Context, result: GeneratedResult) {
    val uris = withContext(Dispatchers.IO) {
        result.parts.map { part -> writeShareCache(context, part.bytes, part.fileName) }
    }
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = result.mime
            putExtra(Intent.EXTRA_STREAM, uris.first())
            clipData = ClipData.newUri(context.contentResolver, result.fileName, uris.first())
        }
    } else {
        val clip = ClipData.newUri(context.contentResolver, result.fileName, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = result.mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
