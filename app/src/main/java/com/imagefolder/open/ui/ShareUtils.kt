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

internal suspend fun shareGeneratedImage(context: Context, result: GeneratedResult) {
    val uri = withContext(Dispatchers.IO) {
        writeShareCache(context, result.bytes, result.fileName)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = result.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, result.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
