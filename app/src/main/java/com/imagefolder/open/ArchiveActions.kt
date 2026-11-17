package com.imagefolder.open
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.LocaleList
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imagefolder.open.core.ArchiveEntry
import com.imagefolder.open.core.ArchiveExtractor
import com.imagefolder.open.core.ArchiveFormat
import com.imagefolder.open.core.ArchiveFormatException
import com.imagefolder.open.core.ArchiveMissingPartsException
import com.imagefolder.open.core.ArchivePart
import com.imagefolder.open.core.ArchivePasswordRequiredException
import com.imagefolder.open.core.ArchiveUnsupportedException
import com.imagefolder.open.core.ArchiveUnsupportedReason
import com.imagefolder.open.core.ArchiveWrongPasswordException
import com.imagefolder.open.core.BundleCodec
import com.imagefolder.open.core.BundleEntry
import com.imagefolder.open.core.BundleFormatException
import com.imagefolder.open.core.BundlePaths
import com.imagefolder.open.core.CarrierCodec
import com.imagefolder.open.core.CarrierFormat
import com.imagefolder.open.core.HiddenFile
import com.imagefolder.open.core.HiddenItem
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.InvalidPasswordException
import com.imagefolder.open.core.OutputName
import com.imagefolder.open.core.PngChunkCodec
import com.imagefolder.open.core.ResultTree
import com.imagefolder.open.core.SplitCarrierCodec
import com.imagefolder.open.core.SplitFormatException
import com.imagefolder.open.core.SplitIntegrityException
import com.imagefolder.open.core.SplitPart
import com.imagefolder.open.data.CacheCleaner
import com.imagefolder.open.data.FolderBudgetExceededException
import com.imagefolder.open.data.FolderExporter
import com.imagefolder.open.data.FolderReader
import com.imagefolder.open.data.FolderScanner
import com.imagefolder.open.data.GallerySaver
import com.imagefolder.open.data.PickedFile
import com.imagefolder.open.data.PickedFolder
import com.imagefolder.open.data.SavedFile
import com.imagefolder.open.data.ScanHit
import com.imagefolder.open.data.ShareClassifier
import com.imagefolder.open.data.SharePlan
import com.imagefolder.open.data.SharedFile
import com.imagefolder.open.data.UriUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archive unpacking actions of [MainViewModel]: turns an extracted archive entry
 * into a folder of result items, including password retries.
 *
 * The ViewModel used to be one 1,600-line file. Pack / extract / archive actions
 * live in sibling files as extension functions so each workflow can be read on
 * its own; the class, its state and its public API are unchanged.
 */

internal fun MainViewModel.unpackArchive(index: Int, password: String? = null) {
    val state = _extract.value
    val item = state.results.getOrNull(index) ?: return
    val app = getApplication<Application>()
    if (item.isDirectory) return
    val format = item.archiveFormat
    if (format == null) {
        showMessage(localizedContext().getString(R.string.msg_unpack_failed, "不是压缩包"))
        return
    }
    if (!ArchiveExtractor.isSupported(format)) {
        showMessage(localizedContext().getString(R.string.msg_unpack_unsupported, format.label))
        return
    }
    if (state.unpackingIndex != null) return

    val candidates = state.results
        .filter { !it.isDirectory }
        .map { ArchivePart(it.name, it.bytes) }
    val splitGroup = ArchiveExtractor.splitGroup(item.name, candidates)
    if (splitGroup != null && splitGroup.missing.isNotEmpty()) {
        val listed = splitGroup.missing.take(6).joinToString(", ") +
            if (splitGroup.missing.size > 6) " …" else ""
        showMessage(localizedContext().getString(R.string.msg_missing_parts, listed))
        return
    }

    _extract.update {
        it.copy(
            unpackingIndex = index,
            unpackPasswordIndex = null,
            unpackPasswordError = false,
        )
    }
    viewModelScope.launch {
        try {
            // Building the result rows is part of the unpack cost: an APK
            // can carry thousands of entries, and mapping them on the main
            // thread was a visible freeze right after the extraction.
            val additions = withContext(Dispatchers.IO) {
                val result = if (splitGroup != null) {
                    ArchiveExtractor.extractGroup(splitGroup, password, app.cacheDir)
                } else {
                    ArchiveExtractor.extract(item.bytes, item.name, password, app.cacheDir)
                }
                buildUnpackedItems(item, result.entries, splitGroup?.baseName)
            }
            refreshCacheInfo()
            _extract.update { current ->
                current.copy(
                    results = current.results + additions,
                    // The unpacked tree is added at the result root, so jump
                    // back there to make the new folder visible.
                    resultFolder = "",
                    unpackingIndex = null,
                    unpackPasswordIndex = null,
                    unpackPasswordError = false,
                )
            }
            showMessage(
                localizedContext().getString(R.string.msg_unpacked, additions.count { !it.isDirectory }),
            )
        } catch (e: ArchivePasswordRequiredException) {
            _extract.update {
                it.copy(
                    unpackingIndex = null,
                    unpackPasswordIndex = index,
                    unpackPasswordError = false,
                )
            }
        } catch (e: ArchiveWrongPasswordException) {
            _extract.update {
                it.copy(
                    unpackingIndex = null,
                    unpackPasswordIndex = index,
                    unpackPasswordError = true,
                )
            }
        } catch (e: ArchiveUnsupportedException) {
            _extract.update { it.copy(unpackingIndex = null) }
            val message = when (e.reason) {
                ArchiveUnsupportedReason.RAR_ENCRYPTED ->
                    localizedContext().getString(R.string.msg_unpack_rar_encrypted)
                ArchiveUnsupportedReason.RAR4_SOLID ->
                    localizedContext().getString(R.string.msg_unpack_rar4_solid)
                null -> e.message
                    ?: localizedContext().getString(R.string.msg_unpack_unsupported, format.label)
            }
            showMessage(message)
        } catch (e: ArchiveMissingPartsException) {
            _extract.update { it.copy(unpackingIndex = null) }
            showMessage(localizedContext().getString(R.string.msg_missing_parts, e.missing.joinToString(", ")))
        } catch (e: ArchiveFormatException) {
            _extract.update { it.copy(unpackingIndex = null) }
            showMessage(localizedContext().getString(R.string.msg_unpack_failed, e.message ?: ""))
        } catch (t: Throwable) {
            _extract.update { it.copy(unpackingIndex = null) }
            showMessage(localizedContext().getString(R.string.msg_unpack_failed, t.message ?: t.javaClass.simpleName))
        }
    }
}

internal fun MainViewModel.unpackWithPassword(password: String) {
    val index = _extract.value.unpackPasswordIndex ?: return
    _extract.update { it.copy(unpackPasswordIndex = null, unpackPasswordError = false) }
    unpackArchive(index, password)
}

internal fun MainViewModel.cancelUnpackPassword() {
    _extract.update { it.copy(unpackPasswordIndex = null, unpackPasswordError = false) }
}

internal fun MainViewModel.buildUnpackedItems(
    item: ExtractItem,
    entries: List<ArchiveEntry>,
    baseNameOverride: String? = null,
): List<ExtractItem> {
    val used = _extract.value.results.mapTo(mutableSetOf()) { it.relativePath }
    val baseName = baseNameOverride
        ?: item.name.substringBeforeLast('.', item.name).ifEmpty { "解压" }
    val prefix = BundlePaths.uniqueDirectoryPath(
        BundlePaths.sanitizeSegment(baseName) + "/",
        used,
    )
    val output = mutableListOf<ExtractItem>()
    output += ExtractItem(
        name = prefix.dropLast(1),
        relativePath = prefix,
        mime = null,
        bytes = ByteArray(0),
        sourceName = item.sourceName,
        isDirectory = true,
    )
    for (entry in entries) {
        val isDirectory = entry.path.endsWith("/")
        val leaf = entry.path.trimEnd('/').substringAfterLast('/').ifEmpty { "文件夹" }
        output += ExtractItem(
            name = leaf,
            relativePath = prefix + entry.path,
            mime = if (isDirectory) {
                null
            } else {
                UriUtils.guessMime(leaf) ?: "application/octet-stream"
            },
            bytes = entry.bytes,
            sourceName = item.sourceName,
            isDirectory = isDirectory,
            archiveFormat = if (isDirectory) {
                null
            } else {
                ArchiveExtractor.detect(leaf, entry.bytes)
                    .takeIf { it != ArchiveFormat.UNKNOWN }
            },
        )
    }
    return output
}
