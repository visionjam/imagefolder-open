package com.imagefolder.open

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.imagefolder.open.core.ArchiveWrongPasswordException
import com.imagefolder.open.core.BundleCodec
import com.imagefolder.open.core.BundleEntry
import com.imagefolder.open.core.BundleFormatException
import com.imagefolder.open.core.BundlePaths
import com.imagefolder.open.core.HiddenFile
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.InvalidPasswordException
import com.imagefolder.open.core.PngChunkCodec
import com.imagefolder.open.data.FolderExporter
import com.imagefolder.open.data.FolderReader
import com.imagefolder.open.data.GallerySaver
import com.imagefolder.open.data.PickedFile
import com.imagefolder.open.data.PickedFolder
import com.imagefolder.open.data.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class GeneratedResult(
    val fileName: String,
    val bytes: ByteArray,
    val saved: Boolean = false,
    val savedLocation: String? = null,
    val bundle: Boolean = false,
    val entryCount: Int = 0,
    val failedFiles: Int = 0,
)

data class PackUiState(
    val cover: PickedFile? = null,
    val payloads: List<PickedFile> = emptyList(),
    val folders: List<PickedFolder> = emptyList(),
    val note: String = "",
    val compress: Boolean = false,
    val encrypt: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    val generating: Boolean = false,
    val result: GeneratedResult? = null,
)

data class ExtractItem(
    val name: String,
    val relativePath: String,
    val mime: String?,
    val bytes: ByteArray,
    val sourceName: String,
    val isDirectory: Boolean = false,
    val savedUri: Uri? = null,
    val archiveFormat: ArchiveFormat? = null,
)

data class ExtractUiState(
    val sources: List<PickedFile> = emptyList(),
    val extracting: Boolean = false,
    val results: List<ExtractItem> = emptyList(),
    val hasExtracted: Boolean = false,
    val needsPassword: Boolean = false,
    val passwordError: Boolean = false,
    val exporting: Boolean = false,
    val unpackingIndex: Int? = null,
    val unpackPasswordIndex: Int? = null,
    val unpackPasswordError: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gallerySaver = GallerySaver(application)

    private val _pack = MutableStateFlow(PackUiState())
    val pack: StateFlow<PackUiState> = _pack.asStateFlow()

    private val _extract = MutableStateFlow(ExtractUiState())
    val extract: StateFlow<ExtractUiState> = _extract.asStateFlow()

    private val _compatMode = MutableStateFlow(prefs.getBoolean(KEY_COMPAT_MODE, false))
    val compatMode: StateFlow<Boolean> = _compatMode.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setCompatMode(enabled: Boolean) {
        _compatMode.value = enabled
        prefs.edit().putBoolean(KEY_COMPAT_MODE, enabled).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setCover(uri: Uri?) {
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
                showMessage(getApplication<Application>().getString(R.string.msg_extract_failed, "无法读取封面"))
            }
        }
    }

    fun addPayloads(uris: List<Uri>) {
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

    fun removePayload(uri: Uri) {
        _pack.update { state -> state.copy(payloads = state.payloads.filterNot { it.uri == uri }) }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val folder = withContext(Dispatchers.IO) {
                runCatching { FolderReader.describe(app, uri) }.getOrNull()
            }
            if (folder == null) {
                showMessage(app.getString(R.string.msg_extract_failed, "无法读取文件夹"))
            } else {
                _pack.update { state ->
                    if (state.folders.any { it.uri == folder.uri }) {
                        state
                    } else {
                        state.copy(folders = state.folders + folder)
                    }
                }
            }
        }
    }

    fun removeFolder(uri: Uri) {
        _pack.update { state -> state.copy(folders = state.folders.filterNot { it.uri == uri }) }
    }

    fun setNote(note: String) {
        _pack.update { it.copy(note = note) }
    }

    fun setCompress(enabled: Boolean) {
        _pack.update { it.copy(compress = enabled) }
    }

    fun setEncrypt(enabled: Boolean) {
        _pack.update {
            if (enabled) {
                it.copy(encrypt = true)
            } else {
                it.copy(encrypt = false, password = "", passwordConfirm = "")
            }
        }
    }

    fun setPassword(value: String) {
        _pack.update { it.copy(password = value) }
    }

    fun setPasswordConfirm(value: String) {
        _pack.update { it.copy(passwordConfirm = value) }
    }

    fun generate() {
        val state = _pack.value
        val app = getApplication<Application>()
        if (state.payloads.isEmpty() && state.folders.isEmpty()) {
            showMessage(app.getString(R.string.msg_need_payload))
            return
        }
        if (state.encrypt) {
            if (state.password.isEmpty()) {
                showMessage(app.getString(R.string.password_required))
                return
            }
            if (state.password != state.passwordConfirm) {
                showMessage(app.getString(R.string.password_mismatch))
                return
            }
        }
        if (state.generating) return

        _pack.update { it.copy(generating = true) }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val cover = state.cover?.let { ImageProcessor.loadCover(app, it.uri) }
                    val coverPng = ImageProcessor.renderHiddenImage(cover, state.note)
                    val fileName = "Tank_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                    val advanced = state.folders.isNotEmpty() || state.encrypt || state.compress

                    if (!advanced) {
                        val hidden = state.payloads.map { picked ->
                            HiddenFile(
                                name = picked.name,
                                mime = picked.mime ?: UriUtils.guessMime(picked.name),
                                bytes = UriUtils.readBytes(app, picked.uri),
                            )
                        }
                        GeneratedResult(
                            fileName = fileName,
                            bytes = PngChunkCodec.insertFiles(coverPng, hidden),
                            bundle = false,
                            entryCount = hidden.size,
                        )
                    } else {
                        val (entries, failed) = buildEntries(app, state.folders, state.payloads)
                        if (entries.isEmpty()) {
                            throw IllegalStateException("没有可打包的内容")
                        }
                        val envelope = BundleCodec.pack(
                            entries = entries,
                            compress = state.compress,
                            password = if (state.encrypt) state.password else null,
                        )
                        val bytes = PngChunkCodec.insertBundle(
                            coverPng = coverPng,
                            name = "图片文件夹.zip",
                            mime = "application/zip",
                            envelope = envelope,
                            encrypted = state.encrypt,
                            compressed = state.compress,
                            entryCount = entries.size,
                        )
                        GeneratedResult(
                            fileName = fileName,
                            bytes = bytes,
                            bundle = true,
                            entryCount = entries.size,
                            failedFiles = failed,
                        )
                    }
                }
                _pack.update { it.copy(generating = false, result = outcome) }
                if (outcome.failedFiles > 0) {
                    showMessage(app.getString(R.string.msg_some_files_failed, outcome.failedFiles))
                }
            } catch (t: Throwable) {
                _pack.update { it.copy(generating = false) }
                showMessage(app.getString(R.string.msg_generate_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun saveResult() {
        val result = _pack.value.result ?: return
        if (result.saved) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val saved = withContext(Dispatchers.IO) {
                    gallerySaver.save(result.bytes, result.fileName, "image/png")
                }
                _pack.update { state ->
                    state.copy(result = state.result?.copy(saved = true, savedLocation = saved.location))
                }
                showMessage(app.getString(R.string.msg_saved_one, saved.location, saved.displayName))
            } catch (t: Throwable) {
                showMessage(app.getString(R.string.msg_save_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun clearResult() {
        _pack.update { it.copy(result = null) }
    }

    fun setExtractSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { UriUtils.describe(app, it) }.getOrNull() }
            }
            _extract.update {
                it.copy(
                    sources = files,
                    results = emptyList(),
                    hasExtracted = false,
                    needsPassword = false,
                    passwordError = false,
                    unpackingIndex = null,
                    unpackPasswordIndex = null,
                    unpackPasswordError = false,
                )
            }
        }
    }

    fun extract(password: String? = null) {
        val state = _extract.value
        val app = getApplication<Application>()
        if (state.sources.isEmpty()) {
            showMessage(app.getString(R.string.msg_need_sources))
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
                                hasExtracted = true,
                                needsPassword = false,
                                passwordError = false,
                            )
                        }
                        if (outcome.items.isEmpty()) {
                            showMessage(app.getString(R.string.msg_no_hidden_files))
                        } else if (outcome.failedBundles > 0) {
                            showMessage(
                                app.getString(R.string.msg_some_files_failed, outcome.failedBundles),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                _extract.update { it.copy(extracting = false, hasExtracted = true) }
                showMessage(app.getString(R.string.msg_extract_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun extractWithPassword(password: String) {
        _extract.update { it.copy(needsPassword = false, passwordError = false) }
        extract(password)
    }

    fun cancelPasswordPrompt() {
        _extract.update { it.copy(needsPassword = false, passwordError = false) }
    }

    fun saveExtracted(index: Int) {
        val item = _extract.value.results.getOrNull(index) ?: return
        val app = getApplication<Application>()
        if (item.isDirectory) {
            showMessage(app.getString(R.string.msg_directory_skipped))
            return
        }
        if (item.savedUri != null) return
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    gallerySaver.saveRelative(item.bytes, item.relativePath, item.mime)
                }
                _extract.update { state ->
                    state.copy(
                        results = state.results.mapIndexed { i, old ->
                            if (i == index) old.copy(savedUri = saved.uri) else old
                        },
                    )
                }
                showMessage(app.getString(R.string.msg_saved_one, saved.location, saved.displayName))
            } catch (t: Throwable) {
                showMessage(app.getString(R.string.msg_save_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun saveAllExtracted() {
        val items = _extract.value.results
        if (items.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val savedUris = mutableMapOf<Int, Uri>()
            var location: String? = null
            var failures = 0
            withContext(Dispatchers.IO) {
                items.forEachIndexed { index, item ->
                    if (item.isDirectory || item.savedUri != null) return@forEachIndexed
                    runCatching { gallerySaver.saveRelative(item.bytes, item.relativePath, item.mime) }
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
                showMessage(app.getString(R.string.msg_saved_to, savedUris.size, location ?: ""))
            }
            if (failures > 0) {
                showMessage(app.getString(R.string.msg_save_failed, "$failures 个文件"))
            }
        }
    }

    fun exportToFolder(treeUri: Uri) {
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
                if (result.failed > 0) {
                    showMessage(
                        app.getString(R.string.msg_export_partial, result.fileCount, result.failed),
                    )
                } else {
                    showMessage(app.getString(R.string.msg_exported, result.fileCount))
                }
            } catch (t: Throwable) {
                _extract.update { it.copy(exporting = false) }
                showMessage(app.getString(R.string.msg_export_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun unpackArchive(index: Int, password: String? = null) {
        val state = _extract.value
        val item = state.results.getOrNull(index) ?: return
        val app = getApplication<Application>()
        if (item.isDirectory) return
        val format = item.archiveFormat
        if (format == null) {
            showMessage(app.getString(R.string.msg_unpack_failed, "不是压缩包"))
            return
        }
        if (!ArchiveExtractor.isSupported(format)) {
            showMessage(app.getString(R.string.msg_unpack_unsupported, format.label))
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
            showMessage(app.getString(R.string.msg_missing_parts, listed))
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
                val result = withContext(Dispatchers.IO) {
                    if (splitGroup != null) {
                        ArchiveExtractor.extractGroup(splitGroup, password, app.cacheDir)
                    } else {
                        ArchiveExtractor.extract(item.bytes, item.name, password, app.cacheDir)
                    }
                }
                val additions = buildUnpackedItems(item, result.entries, splitGroup?.baseName)
                _extract.update { current ->
                    current.copy(
                        results = current.results + additions,
                        unpackingIndex = null,
                        unpackPasswordIndex = null,
                        unpackPasswordError = false,
                    )
                }
                showMessage(
                    app.getString(
                        R.string.msg_unpacked,
                        result.entries.count { !it.path.endsWith("/") },
                    ),
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
                showMessage(e.message ?: app.getString(R.string.msg_unpack_unsupported, format.label))
            } catch (e: ArchiveMissingPartsException) {
                _extract.update { it.copy(unpackingIndex = null) }
                showMessage(app.getString(R.string.msg_missing_parts, e.missing.joinToString(", ")))
            } catch (e: ArchiveFormatException) {
                _extract.update { it.copy(unpackingIndex = null) }
                showMessage(app.getString(R.string.msg_unpack_failed, e.message ?: ""))
            } catch (t: Throwable) {
                _extract.update { it.copy(unpackingIndex = null) }
                showMessage(app.getString(R.string.msg_unpack_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun unpackWithPassword(password: String) {
        val index = _extract.value.unpackPasswordIndex ?: return
        _extract.update { it.copy(unpackPasswordIndex = null, unpackPasswordError = false) }
        unpackArchive(index, password)
    }

    fun cancelUnpackPassword() {
        _extract.update { it.copy(unpackPasswordIndex = null, unpackPasswordError = false) }
    }

    private fun buildUnpackedItems(
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

    private fun buildEntries(
        app: Application,
        folders: List<PickedFolder>,
        payloads: List<PickedFile>,
    ): Pair<List<BundleEntry>, Int> {
        val entries = mutableListOf<BundleEntry>()
        val usedPaths = mutableSetOf<String>()
        var failed = 0
        for (folder in folders) {
            val scan = FolderReader.collect(app, folder, usedPaths)
            entries += scan.entries
            failed += scan.failedFiles
        }
        for (file in payloads) {
            val bytes = runCatching { UriUtils.readBytes(app, file.uri) }.getOrNull()
            if (bytes == null) {
                failed++
                continue
            }
            val name = BundlePaths.sanitizeSegment(file.name)
            val path = BundlePaths.uniqueFilePath(name, usedPaths)
            entries += BundleEntry(path, bytes)
        }
        return entries to failed
    }

    private sealed interface ScanOutcome {
        data class Success(val items: List<ExtractItem>, val failedBundles: Int) : ScanOutcome
        data object PasswordRequired : ScanOutcome
        data object WrongPassword : ScanOutcome
    }

    private fun scanSources(
        app: Application,
        sources: List<PickedFile>,
        password: String?,
    ): ScanOutcome {
        val results = mutableListOf<ExtractItem>()
        var failedBundles = 0
        for (source in sources) {
            val bytes = runCatching { UriUtils.readBytes(app, source.uri) }.getOrNull() ?: continue
            if (!PngChunkCodec.isPng(bytes)) continue
            val hiddenItems = runCatching { PngChunkCodec.extractItems(bytes) }
                .getOrDefault(emptyList())
            for (item in hiddenItems) {
                if (item.format == "ifv2") {
                    if (item.encrypted == true && password.isNullOrEmpty()) {
                        return ScanOutcome.PasswordRequired
                    }
                    val entries = try {
                        BundleCodec.unpack(item.bytes, password)
                    } catch (e: InvalidPasswordException) {
                        return ScanOutcome.WrongPassword
                    } catch (e: BundleFormatException) {
                        failedBundles++
                        continue
                    }
                    for (entry in entries) {
                        val isDirectory = entry.path.endsWith("/")
                        val fileName = entry.path.trimEnd('/').substringAfterLast('/')
                            .ifEmpty { "文件夹" }
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
                            sourceName = source.name,
                            isDirectory = isDirectory,
                            archiveFormat = if (isDirectory) {
                                null
                            } else {
                                ArchiveExtractor.detect(fileName, entry.bytes)
                                    .takeIf { it != ArchiveFormat.UNKNOWN }
                            },
                        )
                    }
                } else {
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
        return ScanOutcome.Success(results, failedBundles)
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_COMPAT_MODE = "compat_mode"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
