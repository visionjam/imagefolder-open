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

const val MAX_PACK_MB = 64
const val MAX_PACK_BYTES = MAX_PACK_MB * 1024L * 1024

/**
 * Longest cover edge in split mode. Every part repeats the cover, so a
 * full-resolution photo would multiply the upload size instead of shrinking
 * it; 1280 px keeps each part small while staying sharp on a phone screen.
 */
const val SPLIT_COVER_MAX_SIZE = 1280

class PayloadTooLargeException : Exception()

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class LanguageMode {
    SYSTEM,
    CHINESE,
    ENGLISH,
}

/** One image produced by a pack run; a split run produces 2-9 of them. */
data class GeneratedPart(
    val fileName: String,
    val bytes: ByteArray,
    val saved: Boolean = false,
    val savedLocation: String? = null,
)

data class GeneratedResult(
    /** What the user typed, kept verbatim so the rename field does not fight the sanitiser. */
    val displayName: String = "",
    /** Unsuffixed base name; part names are derived from it. */
    val baseFileName: String,
    val parts: List<GeneratedPart>,
    val bundle: Boolean = false,
    val entryCount: Int = 0,
    val failedFiles: Int = 0,
    val mime: String = "image/png",
) {
    val fileName: String get() = parts.first().fileName
    val bytes: ByteArray get() = parts.first().bytes
    val saved: Boolean get() = parts.all { it.saved }
    val savedLocation: String? get() = parts.firstOrNull()?.savedLocation
    val isSplit: Boolean get() = parts.size > 1
}

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
    /** User supplied output file name; blank keeps the Tank_<timestamp> default. */
    val outputName: String = "",
    /** True while a picked folder is being described (counting its files). */
    val folderLoading: Boolean = false,
    /** 1 = one image (default); 2-9 = split carrier images. */
    val partCount: Int = 1,
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
    /** Folder currently open in the result browser; "" is the result root. */
    val resultFolder: String = "",
    val hasExtracted: Boolean = false,
    val needsPassword: Boolean = false,
    val passwordError: Boolean = false,
    val exporting: Boolean = false,
    val unpackingIndex: Int? = null,
    val unpackPasswordIndex: Int? = null,
    val unpackPasswordError: Boolean = false,
    val scanning: Boolean = false,
    val scanScanned: Int = 0,
    val scanTotal: Int = 0,
    val scanHits: List<ScanHit> = emptyList(),
    /** URIs of scan hits the user ticked for extraction. */
    val selectedScanUris: Set<String> = emptySet(),
    val scanCompleted: Boolean = false,
    val scanError: String? = null,
)

data class PendingShare(
    val extractSources: List<PickedFile>,
    val packSources: List<PickedFile>,
    val skippedCount: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Resolved per save instead of once, so the folder name follows the
     * in-app language even when it is switched while the app is running.
     */
    private fun gallerySaver(): GallerySaver =
        GallerySaver(getApplication(), localizedContext().getString(R.string.save_folder_name))

    private val _pack = MutableStateFlow(PackUiState())
    val pack: StateFlow<PackUiState> = _pack.asStateFlow()

    private val _extract = MutableStateFlow(ExtractUiState())
    val extract: StateFlow<ExtractUiState> = _extract.asStateFlow()

    private var scanJob: Job? = null

    @Volatile
    private var scanGeneration = 0

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        // Payload copies (shared/ and shared-in/) used to survive forever if the
        // user only ever shared once, because the only cleanup ran inside the
        // share path itself. Wipe them on every process start.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                CacheCleaner.pruneOnStartup(
                    getApplication<Application>().cacheDir,
                    System.currentTimeMillis(),
                )
            }
            refreshCacheInfo()
        }
    }

    private val _allFilesAccess = MutableStateFlow(hasAllFilesAccess())
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private val _compatMode = MutableStateFlow(prefs.getBoolean(KEY_COMPAT_MODE, false))
    val compatMode: StateFlow<Boolean> = _compatMode.asStateFlow()

    /** Opt-in FLAG_SECURE: hides the app in screenshots, recording and recents. */
    private val _secureWindow = MutableStateFlow(prefs.getBoolean(KEY_SECURE_WINDOW, false))
    val secureWindow: StateFlow<Boolean> = _secureWindow.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _languageMode = MutableStateFlow(
        runCatching {
            LanguageMode.valueOf(
                prefs.getString(KEY_LANGUAGE_MODE, LanguageMode.SYSTEM.name)
                    ?: LanguageMode.SYSTEM.name,
            )
        }.getOrDefault(LanguageMode.SYSTEM),
    )
    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _shareWarningSeen = MutableStateFlow(prefs.getBoolean(KEY_SHARE_WARNING_SEEN, false))
    val shareWarningSeen: StateFlow<Boolean> = _shareWarningSeen.asStateFlow()

    fun showMessage(text: String) {
        _message.value = text
    }

    /**
     * Resolves string resources against the *in-app* language.
     *
     * `Application.getString` uses the system configuration, so a user who set
     * the app to Chinese on an English device got a Chinese UI with English
     * snackbars. Mirror `MainActivity.attachBaseContext` here instead.
     */
    private fun localizedContext(): Context {
        val base = getApplication<Application>()
        val tag = when (_languageMode.value) {
            LanguageMode.CHINESE -> "zh-CN"
            LanguageMode.ENGLISH -> "en"
            LanguageMode.SYSTEM -> return base
        }
        return runCatching {
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(tag))
            base.createConfigurationContext(configuration)
        }.getOrDefault(base)
    }

    fun clearMessage() {
        _message.value = null
    }

    fun markShareWarningSeen() {
        _shareWarningSeen.value = true
        prefs.edit().putBoolean(KEY_SHARE_WARNING_SEEN, true).apply()
    }

    private val _activeTab = MutableStateFlow(0)
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _pendingShare = MutableStateFlow<PendingShare?>(null)
    val pendingShare: StateFlow<PendingShare?> = _pendingShare.asStateFlow()

    fun selectTab(index: Int) {
        if (index in 0..2) _activeTab.value = index
    }

    fun handleSharedUris(uris: List<Uri>) {
        val distinctUris = uris.distinct()
        if (distinctUris.isEmpty()) {
            showMessage(localizedContext().getString(R.string.msg_share_no_files))
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val resolved = withContext(Dispatchers.IO) {
                distinctUris.mapNotNull { uri ->
                    runCatching { UriUtils.describe(app, uri) }.getOrNull()
                }
            }
            // Shared URIs only carry a transient read grant; copy them now so
            // generation and extraction do not depend on the grant later.
            val imported = withContext(Dispatchers.IO) {
                resolved.mapIndexedNotNull { index, file -> importSharedFile(app, file, index) }
            }
            val skipped = distinctUris.size - imported.size
            if (imported.isEmpty()) {
                showMessage(localizedContext().getString(R.string.msg_share_no_files))
                return@launch
            }
            val shared = withContext(Dispatchers.IO) {
                imported.map { file ->
                    val inspect = file.mime?.startsWith("image/") == true &&
                        ShareClassifier.shouldInspect(file.size)
                    val hasHidden = inspect && runCatching {
                        val bytes = UriUtils.readAtMost(
                            app,
                            file.uri,
                            ShareClassifier.MAX_INSPECT_BYTES,
                        )
                        bytes != null && CarrierCodec.extractItems(bytes).isNotEmpty()
                    }.getOrDefault(false)
                    SharedFile(
                        uri = file.uri.toString(),
                        name = file.name,
                        mime = file.mime,
                        size = file.size,
                        hasHiddenPayload = hasHidden,
                    )
                }
            }
            val plan = ShareClassifier.classify(shared)
            val byUri = imported.associateBy { it.uri.toString() }
            val incomingExtract = plan.extractUris.mapNotNull(byUri::get)
            val incomingPack = plan.packUris.mapNotNull(byUri::get)
            val current = _pendingShare.value
            if (plan.needsChoice || current != null) {
                val merged = ShareClassifier.merge(
                    existing = current?.let {
                        SharePlan(
                            packUris = it.packSources.map { file -> file.uri.toString() },
                            extractUris = it.extractSources.map { file -> file.uri.toString() },
                        )
                    },
                    incoming = plan,
                )
                val allByUri = (
                    current?.packSources.orEmpty() +
                        current?.extractSources.orEmpty() +
                        incomingPack +
                        incomingExtract
                    ).associateBy { it.uri.toString() }
                _pendingShare.value = PendingShare(
                    extractSources = merged.extractUris.mapNotNull(allByUri::get),
                    packSources = merged.packUris.mapNotNull(allByUri::get),
                    skippedCount = (current?.skippedCount ?: 0) + skipped,
                )
            } else {
                dismissGeneratedResult()
                val added = addPayloadsDirect(incomingPack)
                selectTab(0)
                showMessage(
                    when {
                        added == 0 -> localizedContext().getString(R.string.msg_share_none_added)
                        skipped > 0 -> localizedContext().getString(R.string.msg_share_added_skipped, added, skipped)
                        else -> localizedContext().getString(R.string.msg_share_added, added)
                    },
                )
            }
        }
    }

    fun confirmShareExtract() {
        val pending = _pendingShare.value ?: return
        _pendingShare.value = null
        dismissGeneratedResult()
        resetExtractForNewSources(pending.extractSources)
        selectTab(1)
    }

    fun confirmSharePack() {
        val pending = _pendingShare.value ?: return
        _pendingShare.value = null
        dismissGeneratedResult()
        val added = addPayloadsDirect(pending.packSources)
        selectTab(0)
        if (added > 0) {
            showMessage(localizedContext().getString(R.string.msg_share_added, added))
        }
    }

    fun dismissPendingShare() {
        _pendingShare.value = null
    }

    /** Leaves the generated-result screen so a confirmed share can show its target tab. */
    private fun dismissGeneratedResult() {
        if (_pack.value.result != null) {
            _pack.update { it.copy(result = null) }
        }
    }

    private fun addPayloadsDirect(files: List<PickedFile>): Int {
        var added = 0
        _pack.update { state ->
            val existing = state.payloads.map { it.uri }.toSet()
            val newFiles = files.filterNot { it.uri in existing }
            added = newFiles.size
            state.copy(payloads = state.payloads + newFiles)
        }
        return added
    }

    /**
     * Copies a shared URI into the app cache before its transient read grant
     * expires. A stable cache name keeps repeated shares of the same URI deduplicated.
     */
    private fun importSharedFile(app: Application, file: PickedFile, index: Int): PickedFile? =
        runCatching {
            val root = File(app.cacheDir, "shared-in").apply { mkdirs() }
            val now = System.currentTimeMillis()
            root.listFiles()?.forEach { old ->
                if (now - old.lastModified() > 24L * 60 * 60 * 1000) old.delete()
            }
            val fallback = file.uri.lastPathSegment?.substringAfterLast('/')
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(file.mime ?: "") ?: "bin"
            val displayName = if (file.name == fallback) {
                "shared_${index + 1}.$extension"
            } else {
                file.name
            }
            val safeName = displayName.replace('/', '_')
            val key = Integer.toHexString(file.uri.toString().hashCode())
            val target = File(root, "${key}_$safeName")
            val input = app.contentResolver.openInputStream(file.uri) ?: return@runCatching null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            PickedFile(
                uri = Uri.fromFile(target),
                name = displayName,
                size = target.length(),
                mime = file.mime,
            )
        }.getOrNull()

    fun setCompatMode(enabled: Boolean) {
        _compatMode.value = enabled
        prefs.edit().putBoolean(KEY_COMPAT_MODE, enabled).apply()
    }

    fun setSecureWindow(enabled: Boolean) {
        _secureWindow.value = enabled
        prefs.edit().putBoolean(KEY_SECURE_WINDOW, enabled).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setLanguageMode(mode: LanguageMode) {
        _languageMode.value = mode
        prefs.edit().putString(KEY_LANGUAGE_MODE, mode.name).apply()
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
                showMessage(localizedContext().getString(R.string.msg_extract_failed, "无法读取封面"))
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

    fun removeFolder(uri: Uri) {
        _pack.update { state -> state.copy(folders = state.folders.filterNot { it.uri == uri }) }
    }

    fun setNote(note: String) {
        _pack.update { it.copy(note = note) }
    }

    /** Blank keeps the default `Tank_<timestamp>` name. */
    fun setOutputName(name: String) {
        _pack.update { it.copy(outputName = name) }
    }

    /** Renames every unsaved part before it is saved. */
    fun renameResult(rawName: String) {
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
    fun setPartCount(count: Int) {
        _pack.update { it.copy(partCount = count.coerceIn(1, SplitCarrierCodec.MAX_PARTS)) }
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

    fun saveResult() {
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

    fun clearResult() {
        _pack.update { it.copy(result = null) }
    }

    fun refreshAllFilesAccess() {
        _allFilesAccess.value = hasAllFilesAccess()
        refreshCacheInfo()
    }

    /** Recomputes the size of the app's own cache directory. */
    fun refreshCacheInfo() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) {
                CacheCleaner.totalBytes(getApplication<Application>().cacheDir)
            }
        }
    }

    /** Drops every transient file the app wrote into cacheDir. */
    fun clearCache() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val freed = withContext(Dispatchers.IO) {
                CacheCleaner.clear(app.cacheDir)
            }
            refreshCacheInfo()
            showMessage(localizedContext().getString(R.string.msg_cache_cleared, formatCacheSize(freed)))
        }
    }

    private fun formatCacheSize(bytes: Long): String = when {
        bytes <= 0 -> "0 KB"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun scanFolder(uri: Uri) {
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

    fun scanDirectory(root: File) {
        if (!_allFilesAccess.value) {
            showMessage(localizedContext().getString(R.string.msg_all_files_required))
            return
        }
        startScan { report, continueScan ->
            FolderScanner.scanDirectory(root, report, continueScan)
        }
    }

    private fun startScan(
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

    fun cancelScan() {
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

    fun addScanHitsToExtract() {
        val hits = _extract.value.scanHits
        if (hits.isEmpty()) return
        addScanHits(hits)
    }

    /** Adds only the ticked scan results, so a folder scan is not all-or-nothing. */
    fun addSelectedScanHitsToExtract() {
        val state = _extract.value
        val selected = state.scanHits.filter { it.uri.toString() in state.selectedScanUris }
        if (selected.isEmpty()) {
            showMessage(localizedContext().getString(R.string.msg_scan_none_selected))
            return
        }
        addScanHits(selected)
    }

    fun toggleScanHit(uri: Uri) {
        val key = uri.toString()
        _extract.update { state ->
            val selected = state.selectedScanUris
            state.copy(
                selectedScanUris = if (key in selected) selected - key else selected + key,
            )
        }
    }

    fun selectAllScanHits() {
        _extract.update { state ->
            state.copy(selectedScanUris = state.scanHits.mapTo(mutableSetOf()) { it.uri.toString() })
        }
    }

    fun clearScanSelection() {
        _extract.update { it.copy(selectedScanUris = emptySet()) }
    }

    private fun addScanHits(hits: List<ScanHit>) {
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

    fun setExtractSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { UriUtils.describe(app, it) }.getOrNull() }
            }
            resetExtractForNewSources(files)
        }
    }

    private fun resetExtractForNewSources(sources: List<PickedFile>) {
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
    fun enterResultFolder(path: String) {
        val folder = ResultTree.normalizeFolder(path)
        _extract.update { it.copy(resultFolder = folder) }
    }

    /** Goes one level up; the root is the top of the result browser. */
    fun navigateResultUp() {
        _extract.update { it.copy(resultFolder = ResultTree.parentFolder(it.resultFolder)) }
    }

    fun extract(password: String? = null) {
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

    fun saveAllExtracted() {
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

    fun unpackArchive(index: Int, password: String? = null) {
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

    private fun readPayloadWithinBudget(
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

    private sealed interface ScanOutcome {
        data class Success(
            val items: List<ExtractItem>,
            val failedBundles: Int,
            val splitGaps: List<SplitGap> = emptyList(),
        ) : ScanOutcome

        data object PasswordRequired : ScanOutcome
        data object WrongPassword : ScanOutcome
    }

    /** Parts of one split group that the current selection does not cover. */
    private data class SplitGap(val missing: List<Int>, val count: Int)

    /** One split part found in a source image, plus the JSON header metadata. */
    private data class CollectedSplitPart(
        val sourceName: String,
        val part: SplitPart,
        val encrypted: Boolean?,
    )

    private fun scanSources(
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

    private fun splitGapMessage(gaps: List<SplitGap>): String {
        val context = localizedContext()
        if (gaps.size > 1) {
            return context.getString(R.string.msg_split_incomplete_multi, gaps.size)
        }
        val gap = gaps.first()
        val missing = gap.missing.joinToString(", ") { (it + 1).toString() }
        return context.getString(R.string.msg_split_incomplete, missing, gap.count)
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_COMPAT_MODE = "compat_mode"
        const val KEY_SECURE_WINDOW = "secure_window"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LANGUAGE_MODE = "language_mode"
        const val KEY_SHARE_WARNING_SEEN = "share_warning_seen"
    }
}
