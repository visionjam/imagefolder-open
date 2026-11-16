package com.imagefolder.open.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.imagefolder.open.GeneratedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
