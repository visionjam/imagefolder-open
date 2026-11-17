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
 * Pack-side actions of [MainViewModel]: cover, payloads, folders, note, output
 * name, split parts, generation and saving the generated images.
 *
 * The ViewModel used to be one 1,600-line file. Pack / extract / archive actions
 * live in sibling files as extension functions so each workflow can be read on
 * its own; the class, its state and its public API are unchanged.
 */

internal fun MainViewModel.setCover(uri: Uri?) {
    if (uri == null) {
        _pack.update { it.copy(cover = null) }
        return
    }
    viewModelScope.launch {
        val file = withContext(Dispatchers.IO) {
            runCatching { UriUtils.describe(getApplication<Application>(), uri) }.getOrNull()
        }
        if (file != null) {
            _pack.update { it.copy(cover = file) }
        } else {
            showMessage(localizedContext().getString(R.string.msg_extract_failed, "无法读取封面"))
        }
    }
}

internal fun MainViewModel.addPayloads(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
        val app = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { runCatching { UriUtils.describe(app, it) }.getOrNull() }
        }
        _pack.update { state ->
            val existing = state.payloads.map { it.uri }.toSet()
            state.copy(payloads = state.payloads + files.filterNot { it.uri in existing })
        }
    }
}

internal fun MainViewModel.removePayload(uri: Uri) {
    _pack.update { state -> state.copy(payloads = state.payloads.filterNot { it.uri == uri }) }
}

internal fun MainViewModel.addFolder(uri: Uri) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        _pack.update { it.copy(folderLoading = true) }
        try {
            val folder = withContext(Dispatchers.IO) {
                runCatching { FolderReader.describe(app, uri) }.getOrNull()
            }
            if (folder == null) {
                showMessage(localizedContext().getString(R.string.msg_extract_failed, "无法读取文件夹"))
            } else {
                _pack.update { state ->
                    if (state.folders.any { it.uri == folder.uri }) {
                        state
                    } else {
                        state.copy(folders = state.folders + folder)
                    }
                }
            }
        } finally {
            _pack.update { it.copy(folderLoading = false) }
        }
    }
}

internal fun MainViewModel.removeFolder(uri: Uri) {
    _pack.update { state -> state.copy(folders = state.folders.filterNot { it.uri == uri }) }
}

internal fun MainViewModel.setNote(note: String) {
    _pack.update { it.copy(note = note) }
}

/** Blank keeps the default `Tank_<timestamp>` name. */

internal fun MainViewModel.setOutputName(name: String) {
    _pack.update { it.copy(outputName = name) }
}

/** Renames every unsaved part before it is saved. */

internal fun MainViewModel.renameResult(rawName: String) {
    val current = _pack.value.result ?: return
    if (current.saved) return
    val extension = current.baseFileName.substringAfterLast('.', "png")
    val fallbackStem = current.baseFileName.substringBeforeLast('.', OutputName.DEFAULT_PREFIX)
    val renamed = OutputName.build(rawName, extension, fallbackStem)
    if (renamed == current.baseFileName && rawName == current.displayName) return
    _pack.update {
        it.copy(
            result = current.copy(
                displayName = rawName,
                baseFileName = renamed,
                parts = current.parts.mapIndexed { index, part ->
                    if (part.saved) {
                        part
                    } else {
                        part.copy(
                            fileName = SplitCarrierCodec.partFileName(
                                renamed,
                                index,
                                current.parts.size,
                            ),
                        )
                    }
                },
            ),
        )
    }
}

/** 1 keeps the classic single image; 2-9 split the bundle across images. */

internal fun MainViewModel.setPartCount(count: Int) {
    _pack.update { it.copy(partCount = count.coerceIn(1, SplitCarrierCodec.MAX_PARTS)) }
}

internal fun MainViewModel.setCompress(enabled: Boolean) {
    _pack.update { it.copy(compress = enabled) }
}

internal fun MainViewModel.setEncrypt(enabled: Boolean) {
    _pack.update {
        if (enabled) {
            it.copy(encrypt = true)
        } else {
            it.copy(encrypt = false, password = "", passwordConfirm = "")
        }
    }
}

internal fun MainViewModel.setPassword(value: String) {
    _pack.update { it.copy(password = value) }
}

internal fun MainViewModel.setPasswordConfirm(value: String) {
    _pack.update { it.copy(passwordConfirm = value) }
}

internal fun MainViewModel.generate() {
    val state = _pack.value
    val app = getApplication<Application>()
    if (state.payloads.isEmpty() && state.folders.isEmpty()) {
        showMessage(localizedContext().getString(R.string.msg_need_payload))
        return
    }
    if (state.encrypt) {
        if (state.password.isEmpty()) {
            showMessage(localizedContext().getString(R.string.password_required))
            return
        }
        if (state.password != state.passwordConfirm) {
            showMessage(localizedContext().getString(R.string.password_mismatch))
            return
        }
    }
    if (state.generating) return

    _pack.update { it.copy(generating = true) }
    viewModelScope.launch {
        try {
            val outcome = withContext(Dispatchers.IO) {
                val partCount = state.partCount.coerceIn(1, SplitCarrierCodec.MAX_PARTS)
                val split = partCount > 1
                val carrier = state.cover
                    ?.let { ImageProcessor.detectCarrier(app, it.uri) }
                    ?: CarrierFormat.PNG
                // Split parts repeat the cover, so keep it small: a 4096 px
                // photo would make every part bigger than the payload slice.
                val cover = state.cover?.let {
                    ImageProcessor.loadCover(
                        app,
                        it.uri,
                        if (split) SPLIT_COVER_MAX_SIZE else ImageProcessor.MAX_CARRIER_SIZE,
                    )
                }
                val coverBytes = ImageProcessor.renderHiddenImage(cover, state.note, carrier)
                val defaultStem = OutputName.DEFAULT_PREFIX +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val baseFileName = OutputName.build(
                    rawName = state.outputName,
                    extension = carrier.extension,
                    fallbackStem = defaultStem,
                )
                val displayName = state.outputName.ifBlank {
                    baseFileName.substringBeforeLast('.')
                }
                val advanced = state.folders.isNotEmpty() || state.encrypt ||
                    state.compress || split

                fun buildResult(
                    images: List<ByteArray>,
                    bundle: Boolean,
                    entries: Int,
                    failed: Int,
                ): GeneratedResult = GeneratedResult(
                    displayName = displayName,
                    baseFileName = baseFileName,
                    parts = images.mapIndexed { index, bytes ->
                        GeneratedPart(
                            fileName = SplitCarrierCodec.partFileName(
                                baseFileName,
                                index,
                                images.size,
                            ),
                            bytes = bytes,
                        )
                    },
                    bundle = bundle,
                    entryCount = entries,
                    failedFiles = failed,
                    mime = carrier.mime,
                )

                if (!advanced) {
                    val hidden = mutableListOf<HiddenFile>()
                    var simpleBytes = 0L
                    for (picked in state.payloads) {
                        val bytes = readPayloadWithinBudget(
                            app,
                            picked,
                            MAX_PACK_BYTES - simpleBytes,
                        )
                        simpleBytes += bytes.size
                        hidden += HiddenFile(
                            name = picked.name,
                            mime = picked.mime ?: UriUtils.guessMime(picked.name),
                            bytes = bytes,
                        )
                    }
                    return@withContext buildResult(
                        images = listOf(PngChunkCodec.insertFiles(coverBytes, hidden)),
                        bundle = false,
                        entries = hidden.size,
                        failed = 0,
                    )
                }

                val (entries, failed) = buildEntries(app, state.folders, state.payloads)
                if (entries.isEmpty()) {
                    throw IllegalStateException("没有可打包的内容")
                }
                val envelope = BundleCodec.pack(
                    entries = entries,
                    compress = state.compress,
                    password = if (state.encrypt) state.password else null,
                )
                val payloads = if (split) {
                    SplitCarrierCodec.split(envelope, partCount)
                } else {
                    listOf(envelope)
                }
                val images = payloads.mapIndexed { index, payload ->
                    CarrierCodec.insertItem(
                        coverBytes = coverBytes,
                        format = carrier,
                        item = HiddenItem(
                            name = "图片文件夹.zip",
                            mime = "application/zip",
                            bytes = payload,
                            format = if (split) SplitCarrierCodec.FORMAT else "ifv2",
                            encrypted = state.encrypt,
                            compressed = state.compress,
                            entryCount = entries.size,
                            partIndex = if (split) index + 1 else null,
                            partCount = if (split) payloads.size else null,
                        ),
                    )
                }
                buildResult(
                    images = images,
                    bundle = true,
                    entries = entries.size,
                    failed = failed,
                )
            }
            _pack.update { it.copy(generating = false, result = outcome) }
            if (outcome.failedFiles > 0) {
                showMessage(localizedContext().getString(R.string.msg_some_files_failed, outcome.failedFiles))
            }
        } catch (e: PayloadTooLargeException) {
            _pack.update { it.copy(generating = false) }
            showMessage(localizedContext().getString(R.string.msg_payload_too_large, MAX_PACK_MB))
        } catch (e: FolderBudgetExceededException) {
            _pack.update { it.copy(generating = false) }
            showMessage(localizedContext().getString(R.string.msg_payload_too_large, MAX_PACK_MB))
        } catch (t: Throwable) {
            _pack.update { it.copy(generating = false) }
            showMessage(localizedContext().getString(R.string.msg_generate_failed, t.message ?: t.javaClass.simpleName))
        }
    }
}

internal fun MainViewModel.saveResult() {
    val result = _pack.value.result ?: return
    if (result.saved) return
    viewModelScope.launch {
        val app = getApplication<Application>()
        val saver = gallerySaver()
        val saved = mutableMapOf<Int, SavedFile>()
        var failure: Throwable? = null
        withContext(Dispatchers.IO) {
            result.parts.forEachIndexed { index, part ->
                if (part.saved) return@forEachIndexed
                try {
                    saved[index] = saver.save(part.bytes, part.fileName, result.mime)
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                }
            }
        }
        if (saved.isNotEmpty()) {
            _pack.update { state ->
                val current = state.result ?: return@update state
                state.copy(
                    result = current.copy(
                        parts = current.parts.mapIndexed { index, part ->
                            val entry = saved[index]
                            if (entry == null) {
                                part
                            } else {
                                part.copy(saved = true, savedLocation = entry.location)
                            }
                        },
                    ),
                )
            }
            val first = saved.values.first()
            if (result.parts.size > 1) {
                showMessage(
                    localizedContext().getString(
                        R.string.msg_saved_parts,
                        saved.size,
                        first.location,
                    ),
                )
            } else {
                showMessage(
                    localizedContext().getString(
                        R.string.msg_saved_one,
                        first.location,
                        first.displayName,
                    ),
                )
            }
        }
        failure?.let {
            showMessage(
                localizedContext().getString(
                    R.string.msg_save_failed,
                    it.message ?: it.javaClass.simpleName,
                ),
            )
        }
    }
}

internal fun MainViewModel.clearResult() {
    _pack.update { it.copy(result = null) }
}

internal fun MainViewModel.buildEntries(
    app: Application,
    folders: List<PickedFolder>,
    payloads: List<PickedFile>,
): Pair<List<BundleEntry>, Int> {
    val entries = mutableListOf<BundleEntry>()
    val usedPaths = mutableSetOf<String>()
    var failed = 0
    var totalBytes = 0L

    fun addEntries(newEntries: List<BundleEntry>) {
        for (entry in newEntries) {
            totalBytes += entry.bytes.size
            if (totalBytes > MAX_PACK_BYTES) {
                throw PayloadTooLargeException()
            }
            entries += entry
        }
    }

    for (folder in folders) {
        val scan = FolderReader.collect(
            app,
            folder,
            usedPaths,
            MAX_PACK_BYTES - totalBytes,
        )
        addEntries(scan.entries)
        failed += scan.failedFiles
    }
    for (file in payloads) {
        val bytes = try {
            readPayloadWithinBudget(app, file, MAX_PACK_BYTES - totalBytes)
        } catch (e: PayloadTooLargeException) {
            throw e
        } catch (e: Exception) {
            failed++
            continue
        }
        val name = BundlePaths.sanitizeSegment(file.name)
        val path = BundlePaths.uniqueFilePath(name, usedPaths)
        addEntries(listOf(BundleEntry(path, bytes)))
    }
    return entries to failed
}

internal fun MainViewModel.readPayloadWithinBudget(
    app: Application,
    file: PickedFile,
    remaining: Long,
): ByteArray {
    if (remaining <= 0) throw PayloadTooLargeException()
    if (file.size > 0) {
        if (file.size > remaining) throw PayloadTooLargeException()
        return UriUtils.readBytes(app, file.uri)
    }
    return UriUtils.readAtMost(
        app,
        file.uri,
        remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) ?: throw PayloadTooLargeException()
}
