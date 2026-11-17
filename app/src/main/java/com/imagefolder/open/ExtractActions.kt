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
 * Extract-side actions of [MainViewModel]: folder scanning, source selection, the
 * extraction pipeline, result browsing, saving, export and archive unpacking entry
 * points.
 *
 * The ViewModel used to be one 1,600-line file. Pack / extract / archive actions
 * live in sibling files as extension functions so each workflow can be read on
 * its own; the class, its state and its public API are unchanged.
 */

internal fun MainViewModel.scanFolder(uri: Uri) {
    val app = getApplication<Application>()
    startScan { report, continueScan ->
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        FolderScanner.scan(app, uri, report, continueScan)
    }
}

internal fun MainViewModel.scanDirectory(root: File) {
    if (!_allFilesAccess.value) {
        showMessage(localizedContext().getString(R.string.msg_all_files_required))
        return
    }
    startScan { report, continueScan ->
        FolderScanner.scanDirectory(root, report, continueScan)
    }
}

internal fun MainViewModel.startScan(
    scan: suspend (
        report: (scanned: Int, total: Int) -> Unit,
        continueScan: () -> Boolean,
    ) -> List<ScanHit>,
) {
    scanJob?.cancel()
    val generation = ++scanGeneration
    scanJob = viewModelScope.launch {
        val app = getApplication<Application>()
        _extract.update {
            it.copy(
                scanning = true,
                scanScanned = 0,
                scanTotal = 0,
                scanHits = emptyList(),
                scanCompleted = false,
                scanError = null,
            )
        }
        try {
            val hits = withContext(Dispatchers.IO) {
                // Progress used to be pushed for every single file, which
                // flooded the state flow on folders with thousands of
                // images; 120 ms is well below the visible threshold.
                var lastReport = 0L
                scan(
                    { scanned, total ->
                        val now = System.currentTimeMillis()
                        val due = now - lastReport >= 120L || scanned == 0 || scanned >= total
                        if (due && generation == scanGeneration) {
                            lastReport = now
                            _extract.update { state ->
                                state.copy(scanScanned = scanned, scanTotal = total)
                            }
                        }
                    },
                    { generation == scanGeneration && isActive },
                )
            }
            if (generation == scanGeneration) {
                _extract.update {
                    it.copy(
                        scanning = false,
                        scanHits = hits,
                        selectedScanUris = hits.mapTo(mutableSetOf()) { hit ->
                            hit.uri.toString()
                        },
                        scanScanned = it.scanTotal,
                        scanCompleted = true,
                        scanError = null,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == scanGeneration) {
                _extract.update {
                    it.copy(
                        scanning = false,
                        scanCompleted = false,
                        scanError = localizedContext().getString(
                            R.string.msg_scan_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        } finally {
            if (generation == scanGeneration && _extract.value.scanning) {
                _extract.update { it.copy(scanning = false) }
            }
        }
    }
}

internal fun MainViewModel.cancelScan() {
    scanGeneration++
    scanJob?.cancel()
    scanJob = null
    _extract.update {
        it.copy(
            scanning = false,
            scanCompleted = false,
            scanError = null,
            selectedScanUris = emptySet(),
        )
    }
}

internal fun MainViewModel.addScanHitsToExtract() {
    val hits = _extract.value.scanHits
    if (hits.isEmpty()) return
    addScanHits(hits)
}

/** Adds only the ticked scan results, so a folder scan is not all-or-nothing. */

internal fun MainViewModel.addSelectedScanHitsToExtract() {
    val state = _extract.value
    val selected = state.scanHits.filter { it.uri.toString() in state.selectedScanUris }
    if (selected.isEmpty()) {
        showMessage(localizedContext().getString(R.string.msg_scan_none_selected))
        return
    }
    addScanHits(selected)
}

internal fun MainViewModel.toggleScanHit(uri: Uri) {
    val key = uri.toString()
    _extract.update { state ->
        val selected = state.selectedScanUris
        state.copy(
            selectedScanUris = if (key in selected) selected - key else selected + key,
        )
    }
}

internal fun MainViewModel.selectAllScanHits() {
    _extract.update { state ->
        state.copy(selectedScanUris = state.scanHits.mapTo(mutableSetOf()) { it.uri.toString() })
    }
}

internal fun MainViewModel.clearScanSelection() {
    _extract.update { it.copy(selectedScanUris = emptySet()) }
}

internal fun MainViewModel.addScanHits(hits: List<ScanHit>) {
    val files = hits.map { hit ->
        PickedFile(
            uri = hit.uri,
            name = hit.name,
            size = hit.size,
            mime = UriUtils.guessMime(hit.name),
        )
    }
    resetExtractForNewSources(files)
    _extract.update {
        it.copy(
            scanHits = emptyList(),
            selectedScanUris = emptySet(),
            scanCompleted = false,
            scanTotal = 0,
            scanScanned = 0,
            scanError = null,
        )
    }
    showMessage(localizedContext().getString(R.string.msg_scan_added, files.size))
}

internal fun MainViewModel.setExtractSources(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
        val app = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { runCatching { UriUtils.describe(app, it) }.getOrNull() }
        }
        resetExtractForNewSources(files)
    }
}

internal fun MainViewModel.resetExtractForNewSources(sources: List<PickedFile>) {
    _extract.update {
        it.copy(
            sources = sources,
            results = emptyList(),
            resultFolder = "",
            hasExtracted = false,
            needsPassword = false,
            passwordError = false,
            extracting = false,
            exporting = false,
            unpackingIndex = null,
            unpackPasswordIndex = null,
            unpackPasswordError = false,
        )
    }
}

/** Opens [path] (a directory relative path such as "payload/") in the result browser. */

internal fun MainViewModel.enterResultFolder(path: String) {
    val folder = ResultTree.normalizeFolder(path)
    _extract.update { it.copy(resultFolder = folder) }
}

/** Goes one level up; the root is the top of the result browser. */

internal fun MainViewModel.navigateResultUp() {
    _extract.update { it.copy(resultFolder = ResultTree.parentFolder(it.resultFolder)) }
}

internal fun MainViewModel.runExtraction(password: String? = null) {
    val state = _extract.value
    val app = getApplication<Application>()
    if (state.sources.isEmpty()) {
        showMessage(localizedContext().getString(R.string.msg_need_sources))
        return
    }
    if (state.extracting) return

    _extract.update { it.copy(extracting = true, passwordError = false) }
    viewModelScope.launch {
        try {
            val outcome = withContext(Dispatchers.IO) {
                scanSources(app, state.sources, password)
            }
            when (outcome) {
                is ScanOutcome.PasswordRequired -> {
                    _extract.update {
                        it.copy(extracting = false, needsPassword = true, passwordError = false)
                    }
                }
                is ScanOutcome.WrongPassword -> {
                    _extract.update {
                        it.copy(extracting = false, needsPassword = true, passwordError = true)
                    }
                }
                is ScanOutcome.Success -> {
                    _extract.update {
                        it.copy(
                            extracting = false,
                            results = outcome.items,
                            resultFolder = "",
                            hasExtracted = true,
                            needsPassword = false,
                            passwordError = false,
                        )
                    }
                    when {
                        outcome.splitGaps.isNotEmpty() ->
                            showMessage(splitGapMessage(outcome.splitGaps))
                        outcome.items.isEmpty() ->
                            showMessage(localizedContext().getString(R.string.msg_no_hidden_files))
                        outcome.failedBundles > 0 -> showMessage(
                            localizedContext().getString(
                                R.string.msg_some_files_failed,
                                outcome.failedBundles,
                            ),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            _extract.update { it.copy(extracting = false, hasExtracted = true) }
            showMessage(localizedContext().getString(R.string.msg_extract_failed, t.message ?: t.javaClass.simpleName))
        }
    }
}

internal fun MainViewModel.extractWithPassword(password: String) {
    _extract.update { it.copy(needsPassword = false, passwordError = false) }
    runExtraction(password)
}

internal fun MainViewModel.cancelPasswordPrompt() {
    _extract.update { it.copy(needsPassword = false, passwordError = false) }
}

internal fun MainViewModel.saveExtracted(index: Int) {
    val item = _extract.value.results.getOrNull(index) ?: return
    val app = getApplication<Application>()
    if (item.isDirectory) {
        showMessage(localizedContext().getString(R.string.msg_directory_skipped))
        return
    }
    if (item.savedUri != null) return
    viewModelScope.launch {
        try {
            val saver = gallerySaver()
            val saved = withContext(Dispatchers.IO) {
                saver.saveRelative(item.bytes, item.relativePath, item.mime)
            }
            _extract.update { state ->
                state.copy(
                    results = state.results.mapIndexed { i, old ->
                        if (i == index) old.copy(savedUri = saved.uri) else old
                    },
                )
            }
            showMessage(localizedContext().getString(R.string.msg_saved_one, saved.location, saved.displayName))
        } catch (t: Throwable) {
            showMessage(localizedContext().getString(R.string.msg_save_failed, t.message ?: t.javaClass.simpleName))
        }
    }
}

internal fun MainViewModel.saveAllExtracted() {
    val items = _extract.value.results
    if (items.isEmpty()) return
    viewModelScope.launch {
        val app = getApplication<Application>()
        val savedUris = mutableMapOf<Int, Uri>()
        val saver = gallerySaver()
        var location: String? = null
        var failures = 0
        withContext(Dispatchers.IO) {
            items.forEachIndexed { index, item ->
                if (item.isDirectory || item.savedUri != null) return@forEachIndexed
                runCatching { saver.saveRelative(item.bytes, item.relativePath, item.mime) }
                    .onSuccess { saved ->
                        location = location ?: saved.location
                        savedUris[index] = saved.uri
                    }
                    .onFailure { failures += 1 }
            }
        }
        _extract.update { state ->
            state.copy(
                results = state.results.mapIndexed { index, item ->
                    savedUris[index]?.let { item.copy(savedUri = it) } ?: item
                },
            )
        }
        if (savedUris.isNotEmpty()) {
            showMessage(localizedContext().getString(R.string.msg_saved_to, savedUris.size, location ?: ""))
        }
        if (failures > 0) {
            showMessage(localizedContext().getString(R.string.msg_save_failed, "$failures 个文件"))
        }
    }
}

internal fun MainViewModel.exportToFolder(treeUri: Uri) {
    val items = _extract.value.results
    if (items.isEmpty()) return
    if (_extract.value.exporting) return
    _extract.update { it.copy(exporting = true) }
    viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        try {
            val result = withContext(Dispatchers.IO) {
                FolderExporter.export(app, treeUri, items)
            }
            _extract.update { it.copy(exporting = false) }
            refreshCacheInfo()
            if (result.failed > 0) {
                showMessage(
                    localizedContext().getString(R.string.msg_export_partial, result.fileCount, result.failed),
                )
            } else {
                showMessage(localizedContext().getString(R.string.msg_exported, result.fileCount))
            }
        } catch (t: Throwable) {
            _extract.update { it.copy(exporting = false) }
            showMessage(localizedContext().getString(R.string.msg_export_failed, t.message ?: t.javaClass.simpleName))
        }
    }
}

internal sealed interface ScanOutcome {
    data class Success(
        val items: List<ExtractItem>,
        val failedBundles: Int,
        val splitGaps: List<SplitGap> = emptyList(),
    ) : ScanOutcome

    data object PasswordRequired : ScanOutcome
    data object WrongPassword : ScanOutcome
}

/** Parts of one split group that the current selection does not cover. */
internal data class SplitGap(val missing: List<Int>, val count: Int)

/** One split part found in a source image, plus the JSON header metadata. */

internal data class CollectedSplitPart(
    val sourceName: String,
    val part: SplitPart,
    val encrypted: Boolean?,
)

internal fun MainViewModel.scanSources(
    app: Application,
    sources: List<PickedFile>,
    password: String?,
): ScanOutcome {
    val results = mutableListOf<ExtractItem>()
    val splitParts = mutableListOf<CollectedSplitPart>()
    var failedBundles = 0

    fun appendEntries(entries: List<BundleEntry>, sourceName: String) {
        for (entry in entries) {
            val isDirectory = entry.path.endsWith("/")
            val fileName = entry.path.trimEnd('/').substringAfterLast('/').ifEmpty { "文件夹" }
            val mime = if (isDirectory) {
                null
            } else {
                UriUtils.guessMime(fileName) ?: "application/octet-stream"
            }
            results += ExtractItem(
                name = fileName,
                relativePath = entry.path,
                mime = mime,
                bytes = entry.bytes,
                sourceName = sourceName,
                isDirectory = isDirectory,
                archiveFormat = if (isDirectory) {
                    null
                } else {
                    ArchiveExtractor.detect(fileName, entry.bytes)
                        .takeIf { it != ArchiveFormat.UNKNOWN }
                },
            )
        }
    }

    for (source in sources) {
        val bytes = runCatching { UriUtils.readBytes(app, source.uri) }.getOrNull() ?: continue
        val hiddenItems = runCatching { CarrierCodec.extractItems(bytes) }
            .getOrDefault(emptyList())
        for (item in hiddenItems) {
            when (item.format) {
                SplitCarrierCodec.FORMAT -> {
                    val part = try {
                        SplitCarrierCodec.parse(item.bytes)
                    } catch (e: SplitFormatException) {
                        failedBundles++
                        null
                    } catch (e: SplitIntegrityException) {
                        failedBundles++
                        null
                    }
                    if (part != null) {
                        splitParts += CollectedSplitPart(source.name, part, item.encrypted)
                    }
                }
                "ifv2" -> {
                    if (item.encrypted == true && password.isNullOrEmpty()) {
                        return ScanOutcome.PasswordRequired
                    }
                    val entries = try {
                        BundleCodec.unpack(item.bytes, password)
                    } catch (e: InvalidPasswordException) {
                        return ScanOutcome.WrongPassword
                    } catch (e: BundleFormatException) {
                        failedBundles++
                        null
                    }
                    if (entries != null) appendEntries(entries, source.name)
                }
                else -> {
                    results += ExtractItem(
                        name = item.name,
                        relativePath = item.name,
                        mime = item.mime,
                        bytes = item.bytes,
                        sourceName = source.name,
                        archiveFormat = ArchiveExtractor.detect(item.name, item.bytes)
                            .takeIf { it != ArchiveFormat.UNKNOWN },
                    )
                }
            }
        }
    }

    // Split parts are useless alone: group them by the random group id and
    // only unpack groups that are complete. Gaps are reported to the user.
    val gaps = mutableListOf<SplitGap>()
    val groups = LinkedHashMap<String, MutableList<CollectedSplitPart>>()
    for (collected in splitParts) {
        groups.getOrPut(collected.part.header.groupId) { mutableListOf() } += collected
    }
    for (group in groups.values) {
        val first = group.first()
        val unique = group.distinctBy { it.part.header.index }
        val missing = (0 until first.part.header.count)
            .filter { index -> unique.none { it.part.header.index == index } }
        if (missing.isNotEmpty()) {
            gaps += SplitGap(missing, first.part.header.count)
            continue
        }
        if (first.encrypted == true && password.isNullOrEmpty()) {
            return ScanOutcome.PasswordRequired
        }
        val envelope = try {
            SplitCarrierCodec.reassemble(unique.map { it.part })
        } catch (e: SplitIntegrityException) {
            failedBundles++
            continue
        } catch (e: SplitFormatException) {
            failedBundles++
            continue
        }
        val entries = try {
            BundleCodec.unpack(envelope, password)
        } catch (e: InvalidPasswordException) {
            return ScanOutcome.WrongPassword
        } catch (e: BundleFormatException) {
            failedBundles++
            null
        }
        if (entries != null) appendEntries(entries, unique.first().sourceName)
    }
    return ScanOutcome.Success(results, failedBundles, gaps)
}

internal fun MainViewModel.splitGapMessage(gaps: List<SplitGap>): String {
    val context = localizedContext()
    if (gaps.size > 1) {
        return context.getString(R.string.msg_split_incomplete_multi, gaps.size)
    }
    val gap = gaps.first()
    val missing = gap.missing.joinToString(", ") { (it + 1).toString() }
    return context.getString(R.string.msg_split_incomplete, missing, gap.count)
}
