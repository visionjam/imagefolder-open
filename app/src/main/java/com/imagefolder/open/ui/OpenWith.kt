package com.imagefolder.open.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.imagefolder.open.ExtractItem
import com.imagefolder.open.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands an extracted file to the app the ROM already associates with it.
 *
 * Extracted entries live in memory until the user saves them, so an unsaved
 * file is first written to the FileProvider share cache. The intent is started
 * directly instead of through a chooser: Android opens the user's default app
 * for the type, and shows its own "Open with" picker when there is no default.
 * Only when nothing handles the type do we widen to the wildcard MIME type so
 * file managers can still open it.
 *
 * The MIME comes from [handoffMime]: entries Android cannot type by name
 * (`README`, `.md`, `.log`, ...) are sniffed as text instead of being sent as
 * `application/octet-stream`, which used to resolve to a file manager and look
 * like "nothing opens this file". The cache copy also gets an extension via
 * [handoffFileName] for receivers that sniff by name.
 */
internal suspend fun openWithAnotherApp(context: Context, item: ExtractItem) {
    val mime = handoffMime(item)
    val uri = item.savedUri ?: withContext(Dispatchers.IO) {
        writeOpenCache(context, item.bytes, handoffFileName(item.name, mime))
    }

    fun viewIntent(type: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, type)
        // Some receivers only inherit the read grant through ClipData, not the
        // data URI alone.
        clipData = ClipData.newRawUri(item.name.ifBlank { "file" }, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val types = if (mime == "*/*") listOf(mime) else listOf(mime, "*/*")
    for (type in types) {
        try {
            context.startActivity(viewIntent(type))
            return
        } catch (_: ActivityNotFoundException) {
            // No handler for this type; try the wider */* fallback.
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.msg_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
    }
    Toast.makeText(context, R.string.msg_no_app_found, Toast.LENGTH_SHORT).show()
}
