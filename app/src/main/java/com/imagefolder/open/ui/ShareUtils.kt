package com.imagefolder.open.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.imagefolder.open.GeneratedResult

internal fun writeShareCache(context: Context, bytes: ByteArray, fileName: String): Uri {
    val directory = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(directory, fileName.replace('/', '_'))
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

internal fun shareGeneratedImage(context: Context, result: GeneratedResult) {
    val uri = writeShareCache(context, result.bytes, result.fileName)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = result.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
