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
    internal val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Resolved per save instead of once, so the folder name follows the
     * in-app language even when it is switched while the app is running.
     */
    internal fun gallerySaver(): GallerySaver =
        GallerySaver(getApplication(), localizedContext().getString(R.string.save_folder_name))

    internal val _pack = MutableStateFlow(PackUiState())

    val pack: StateFlow<PackUiState> = _pack.asStateFlow()

    internal val _extract = MutableStateFlow(ExtractUiState())

    val extract: StateFlow<ExtractUiState> = _extract.asStateFlow()

    internal var scanJob: Job? = null

    @Volatile
    internal var scanGeneration = 0

    internal val _cacheBytes = MutableStateFlow(0L)

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

    internal val _allFilesAccess = MutableStateFlow(hasAllFilesAccess())

    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    internal val _compatMode = MutableStateFlow(prefs.getBoolean(KEY_COMPAT_MODE, false))

    val compatMode: StateFlow<Boolean> = _compatMode.asStateFlow()

    /** Opt-in FLAG_SECURE: hides the app in screenshots, recording and recents. */
    internal val _secureWindow = MutableStateFlow(prefs.getBoolean(KEY_SECURE_WINDOW, false))

    val secureWindow: StateFlow<Boolean> = _secureWindow.asStateFlow()

    internal val _themeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM),
    )

    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    internal val _languageMode = MutableStateFlow(
        runCatching {
            LanguageMode.valueOf(
                prefs.getString(KEY_LANGUAGE_MODE, LanguageMode.SYSTEM.name)
                    ?: LanguageMode.SYSTEM.name,
            )
        }.getOrDefault(LanguageMode.SYSTEM),
    )

    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    internal val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    internal val _shareWarningSeen = MutableStateFlow(prefs.getBoolean(KEY_SHARE_WARNING_SEEN, false))

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
    internal fun localizedContext(): Context {
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

    /**
     * One-off disclaimer gate, mirroring the original app's first-launch
     * dialog. Stored in preferences so it is shown exactly once; the About page
     * keeps the same text available afterwards.
     */
    internal val _agreementAccepted =
        MutableStateFlow(prefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false))

    val agreementAccepted: StateFlow<Boolean> = _agreementAccepted.asStateFlow()

    fun acceptAgreement() {
        _agreementAccepted.value = true
        prefs.edit().putBoolean(KEY_AGREEMENT_ACCEPTED, true).apply()
    }

    internal val _activeTab = MutableStateFlow(0)

    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    internal val _pendingShare = MutableStateFlow<PendingShare?>(null)

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
    internal fun dismissGeneratedResult() {
        if (_pack.value.result != null) {
            _pack.update { it.copy(result = null) }
        }
    }

    internal fun addPayloadsDirect(files: List<PickedFile>): Int {
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
    internal fun importSharedFile(app: Application, file: PickedFile, index: Int): PickedFile? =
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

    internal fun formatCacheSize(bytes: Long): String = when {
        bytes <= 0 -> "0 KB"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    internal fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    internal companion object {
        const val PREFS_NAME = "settings"
        const val KEY_COMPAT_MODE = "compat_mode"
        const val KEY_SECURE_WINDOW = "secure_window"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LANGUAGE_MODE = "language_mode"
        const val KEY_SHARE_WARNING_SEEN = "share_warning_seen"
        const val KEY_AGREEMENT_ACCEPTED = "agreement_accepted"
    }
}
