package com.imagefolder.open

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imagefolder.open.core.HiddenFile
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.PngChunkCodec
import com.imagefolder.open.data.GallerySaver
import com.imagefolder.open.data.PickedFile
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

data class GeneratedResult(
    val fileName: String,
    val bytes: ByteArray,
    val saved: Boolean = false,
    val savedLocation: String? = null,
)

data class PackUiState(
    val cover: PickedFile? = null,
    val payloads: List<PickedFile> = emptyList(),
    val note: String = "",
    val generating: Boolean = false,
    val result: GeneratedResult? = null,
)

data class ExtractItem(
    val name: String,
    val mime: String?,
    val bytes: ByteArray,
    val sourceName: String,
    val savedUri: Uri? = null,
)

data class ExtractUiState(
    val sources: List<PickedFile> = emptyList(),
    val extracting: Boolean = false,
    val results: List<ExtractItem> = emptyList(),
    val hasExtracted: Boolean = false,
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

    fun setNote(note: String) {
        _pack.update { it.copy(note = note) }
    }

    fun generate() {
        val state = _pack.value
        val app = getApplication<Application>()
        if (state.payloads.isEmpty()) {
            showMessage(app.getString(R.string.msg_need_payload))
            return
        }
        if (state.generating) return

        _pack.update { it.copy(generating = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cover = state.cover?.let { ImageProcessor.loadCover(app, it.uri) }
                    val coverPng = ImageProcessor.renderHiddenImage(cover, state.note)
                    val hiddenFiles = state.payloads.map { picked ->
                        HiddenFile(
                            name = picked.name,
                            mime = picked.mime ?: UriUtils.guessMime(picked.name),
                            bytes = UriUtils.readBytes(app, picked.uri),
                        )
                    }
                    val bytes = PngChunkCodec.insertFiles(coverPng, hiddenFiles)
                    val fileName = "Tank_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                    GeneratedResult(fileName = fileName, bytes = bytes)
                }
                _pack.update { it.copy(generating = false, result = result) }
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
            _extract.update { it.copy(sources = files, results = emptyList(), hasExtracted = false) }
        }
    }

    fun extract() {
        val state = _extract.value
        val app = getApplication<Application>()
        if (state.sources.isEmpty()) {
            showMessage(app.getString(R.string.msg_need_sources))
            return
        }
        if (state.extracting) return

        _extract.update { it.copy(extracting = true) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val all = mutableListOf<ExtractItem>()
                    for (source in state.sources) {
                        val bytes = runCatching { UriUtils.readBytes(app, source.uri) }.getOrNull() ?: continue
                        if (!PngChunkCodec.isPng(bytes)) continue
                        runCatching { PngChunkCodec.extractFiles(bytes) }
                            .getOrDefault(emptyList())
                            .forEach { hidden ->
                                all += ExtractItem(
                                    name = hidden.name,
                                    mime = hidden.mime,
                                    bytes = hidden.bytes,
                                    sourceName = source.name,
                                )
                            }
                    }
                    all
                }
                _extract.update { it.copy(extracting = false, results = items, hasExtracted = true) }
                if (items.isEmpty()) showMessage(app.getString(R.string.msg_no_hidden_files))
            } catch (t: Throwable) {
                _extract.update { it.copy(extracting = false, hasExtracted = true) }
                showMessage(app.getString(R.string.msg_extract_failed, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun saveExtracted(index: Int) {
        val item = _extract.value.results.getOrNull(index) ?: return
        if (item.savedUri != null) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val saved = withContext(Dispatchers.IO) {
                    gallerySaver.save(item.bytes, item.name, item.mime)
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
                    if (item.savedUri != null) return@forEachIndexed
                    runCatching { gallerySaver.save(item.bytes, item.name, item.mime) }
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

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_COMPAT_MODE = "compat_mode"
    }
}
