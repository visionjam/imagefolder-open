package com.imagefolder.open.ui

import com.imagefolder.open.*

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.imagefolder.open.ExtractItem
import com.imagefolder.open.ExtractUiState
import com.imagefolder.open.MainViewModel
import com.imagefolder.open.R
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.core.ResultEntry
import com.imagefolder.open.core.ResultNode
import com.imagefolder.open.core.ResultTree
import com.imagefolder.open.data.ScanHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtractScreen(
    state: ExtractUiState,
    compatMode: Boolean,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewItem by remember { mutableStateOf<ExtractItem?>(null) }

    // Both hand-offs write an unsaved entry into the FileProvider cache first.
    // They are wrapped here so a failed write or a missing handler shows a
    // snackbar instead of crashing the composition scope.
    val openWith: (ExtractItem) -> Unit = { item ->
        scope.launch {
            runCatching { openWithAnotherApp(context, item) }
                .onFailure { viewModel.showMessage(context.getString(R.string.msg_open_failed)) }
        }
    }
    val shareItems: (List<ExtractItem>) -> Unit = { items ->
        scope.launch {
            try {
                val shared = shareExtractedFiles(
                    context,
                    items,
                    context.getString(R.string.share_action),
                )
                if (!shared) viewModel.showMessage(context.getString(R.string.msg_share_no_files))
            } catch (t: Throwable) {
                viewModel.showMessage(
                    context.getString(
                        R.string.msg_share_failed,
                        t.message ?: t.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    // Results are stored flat ("payload/sub/a.txt"); derive one folder level at
    // a time so folder rows can be opened instead of being dead labels.
    val treeEntries = remember(state.results) {
        state.results.mapIndexed { index, item ->
            ResultEntry(item.relativePath, item.isDirectory, index)
        }
    }
    val visibleNodes = remember(treeEntries, state.resultFolder) {
        ResultTree.children(treeEntries, state.resultFolder)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris ->
        viewModel.setExtractSources(uris)
    }
    // Compat mode mirrors the original app on the extract side too: one image
    // from the gallery, for devices whose multi-select picker misbehaves.
    val compatImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.setExtractSources(listOf(uri))
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.exportToFolder(uri)
    }
    val scanPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.scanFolder(uri)
    }
    val allFilesAccess by viewModel.allFilesAccess.collectAsState()
    var showBrowser by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAllFilesAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A single LazyColumn: unpacking an APK produces thousands of entries, and
    // laying out every row of a scrolling Column at once froze the UI.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.extract_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.extract_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (state.sources.isEmpty()) {
                        stringResource(R.string.extract_empty)
                    } else {
                        stringResource(R.string.extract_selected, state.sources.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.sources.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val request = PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    )
                    if (compatMode) {
                        compatImagePicker.launch(request)
                    } else {
                        imagePicker.launch(request)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (compatMode) R.string.pick_extract_compat else R.string.pick_extract,
                    ),
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    if (allFilesAccess) {
                        showBrowser = true
                    } else {
                        scanPicker.launch(null)
                    }
                },
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_folder))
            }
        }

        item { AllFilesAccessBlock(allFilesAccess = allFilesAccess, onUseSystemPicker = { scanPicker.launch(null) }) }

        if (state.scanning) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.scanning_progress,
                            state.scanScanned,
                            state.scanTotal,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.scanTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.scanScanned.toFloat() / state.scanTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = viewModel::cancelScan) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        } else if (state.scanHits.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.scan_results, state.scanHits.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::selectAllScanHits) {
                            Text(stringResource(R.string.scan_select_all))
                        }
                        TextButton(onClick = viewModel::clearScanSelection) {
                            Text(stringResource(R.string.scan_select_none))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::addSelectedScanHitsToExtract,
                            enabled = state.selectedScanUris.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    R.string.scan_add_selected,
                                    state.selectedScanUris.size,
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::addScanHitsToExtract,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.scan_add_all))
                        }
                    }
                }
            }
            items(state.scanHits) { hit ->
                ScanHitRow(
                    hit = hit,
                    selected = hit.uri.toString() in state.selectedScanUris,
                    onToggle = { viewModel.toggleScanHit(hit.uri) },
                )
            }
        } else if (state.scanError != null) {
            item {
                Text(
                    text = state.scanError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (state.scanCompleted) {
            item {
                Text(
                    text = stringResource(R.string.scan_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Button(
                onClick = { viewModel.runExtraction() },
                enabled = state.sources.isNotEmpty() && !state.extracting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.extracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.extract_running))
                } else {
                    Icon(Icons.Outlined.Unarchive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.extract_button))
                }
            }
        }

        if (state.results.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.extract_results),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.extract_result_count, state.results.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::saveAllExtracted) {
                            Text(stringResource(R.string.save_all))
                        }
                    }
                    OutlinedButton(
                        onClick = { exportPicker.launch(null) },
                        enabled = !state.exporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.exporting))
                        } else {
                            Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export_to_folder))
                        }
                    }
                }
            }
            if (state.resultFolder.isNotEmpty()) {
                item {
                    ResultBreadcrumb(
                        folder = state.resultFolder,
                        onUp = viewModel::navigateResultUp,
                    )
                }
            }
            if (visibleNodes.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.result_folder_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleNodes, key = { it.path }) { node ->
                if (node.isDirectory) {
                    ResultFolderRow(
                        node = node,
                        onOpen = { viewModel.enterResultFolder(node.path) },
                    )
                } else {
                    val index = node.itemIndex
                    if (index in state.results.indices) {
                        val item = state.results[index]
                        ExtractedRow(
                            item = item,
                            unpacking = state.unpackingIndex == index,
                            onPreview = { previewItem = item },
                            onSave = { viewModel.saveExtracted(index) },
                            onShare = { shareItems(listOf(item)) },
                            onOpenWith = { openWith(item) },
                            onUnpack = { viewModel.unpackArchive(index) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    previewItem?.let { item ->
        PreviewDialog(
            item = item,
            onDismiss = { previewItem = null },
            onShare = { shareItems(listOf(item)) },
            onOpenWith = { openWith(item) },
        )
    }

    if (showBrowser) {
        FolderBrowserDialog(
            onDismiss = { showBrowser = false },
            onSelect = { directory ->
                showBrowser = false
                viewModel.scanDirectory(directory)
            },
        )
    }

    if (state.needsPassword) {
        PasswordPromptDialog(
            passwordError = state.passwordError,
            message = stringResource(R.string.password_dialog_message),
            onConfirm = viewModel::extractWithPassword,
            onDismiss = viewModel::cancelPasswordPrompt,
        )
    }

    if (state.unpackPasswordIndex != null) {
        PasswordPromptDialog(
            passwordError = state.unpackPasswordError,
            message = stringResource(R.string.archive_password_message),
            onConfirm = viewModel::unpackWithPassword,
            onDismiss = viewModel::cancelUnpackPassword,
        )
    }
}

@Composable
private fun AllFilesAccessBlock(
    allFilesAccess: Boolean,
    onUseSystemPicker: () -> Unit,
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (allFilesAccess) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.all_files_granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onUseSystemPicker) {
                    Text(stringResource(R.string.use_system_picker))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.all_files_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.all_files_grant))
                }
            }
        }
    } else {
        Text(
            text = stringResource(R.string.all_files_android_11),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanHitRow(
    hit: ScanHit,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(hit.format.name)
                        append(" · ")
                        append(stringResource(R.string.scan_entries, hit.entries))
                        if (hit.encrypted) {
                            append(" · ")
                            append(stringResource(R.string.scan_encrypted))
                        }
                        val partIndex = hit.partIndex
                        val partCount = hit.partCount
                        if (partIndex != null && partCount != null) {
                            append(" · ")
                            append(stringResource(R.string.scan_split_part, partIndex, partCount))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultBreadcrumb(folder: String, onUp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.result_up),
                )
            }
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = folder.trim('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ResultFolderRow(node: ResultNode, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.folder_items, node.childCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractedRow(
    item: ExtractItem,
    unpacking: Boolean,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onUnpack: () -> Unit,
) {
    val image = isImageMime(item.mime, item.name)
    val previewable = image || isTextFile(item.mime, item.name)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (image) {
                    ResultThumbnail(item = item, onClick = onPreview)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (item.relativePath != item.name) {
                            item.relativePath
                        } else {
                            stringResource(R.string.source_from, item.sourceName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sizeLabel = buildString {
                    append(formatSize(item.bytes.size.toLong()))
                    item.archiveFormat?.let { append(" · ").append(it.label) }
                }
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.Center,
            ) {
                if (previewable) {
                    TextButton(onClick = onPreview) {
                        Text(stringResource(R.string.preview))
                    }
                }
                if (item.savedUri == null) {
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.save))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.saved_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                // Share is the chat/mail hand-off; open-with is the viewer
                // hand-off. Both accept every format, because the in-app
                // preview only understands images and text.
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.share_action))
                }
                TextButton(onClick = onOpenWith) {
                    Text(stringResource(R.string.open_with))
                }
                if (item.archiveFormat != null) {
                    if (unpacking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onUnpack) {
                            Text(stringResource(R.string.unpack))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewDialog(
    item: ExtractItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val image = isImageMime(item.mime, item.name)
    val text = isTextFile(item.mime, item.name)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    image -> ImagePreview(item.bytes)
                    text -> TextPreview(item.bytes)
                    else -> Text(stringResource(R.string.msg_preview_failed))
                }
                Spacer(Modifier.height(8.dp))
                // FlowRow: three buttons do not fit one line on narrow screens
                // once the system font scale is raised.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.share_action))
                    }
                    TextButton(onClick = onOpenWith) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open_with))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(bytes: ByteArray) {
    @Suppress("ProduceStateDoesNotAssignValue") // value is assigned below
    val bitmap by produceState<Bitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) {
            ImageProcessor.decodePreviewBytes(bytes, PREVIEW_MAX_SIZE)
        }
    }
    val preview = bitmap
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(stringResource(R.string.msg_preview_failed))
    }
}

@Composable
private fun TextPreview(bytes: ByteArray) {
    @Suppress("ProduceStateDoesNotAssignValue") // value is assigned below
    val decoded by produceState<String?>(null, bytes) {
        value = withContext(Dispatchers.IO) { decodePreviewText(bytes) }
    }
    val body = decoded
    if (body == null) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        return
    }
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    )
    if (bytes.size > PREVIEW_TEXT_MAX_BYTES) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.preview_text_truncated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordPromptDialog(
    passwordError: Boolean,
    message: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = stringResource(
                                    if (visible) R.string.hide_password else R.string.show_password,
                                ),
                            )
                        }
                    },
                )
                if (passwordError) {
                    Text(
                        text = stringResource(R.string.password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (password.isNotEmpty()) onConfirm(password) },
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Preview decode target for extracted images shown in a dialog. */
private const val PREVIEW_MAX_SIZE = 1080

/** Larger text files are cut off; the dialog is a preview, not an editor. */
private const val PREVIEW_TEXT_MAX_BYTES = 256 * 1024

private fun decodePreviewText(bytes: ByteArray): String {
    val slice = if (bytes.size > PREVIEW_TEXT_MAX_BYTES) {
        bytes.copyOf(PREVIEW_TEXT_MAX_BYTES)
    } else {
        bytes
    }
    return slice.toString(Charsets.UTF_8)
}
