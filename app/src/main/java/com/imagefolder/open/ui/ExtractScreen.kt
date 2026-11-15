package com.imagefolder.open.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExtractScreen(
    state: ExtractUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    var previewItem by remember { mutableStateOf<ExtractItem?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris ->
        viewModel.setExtractSources(uris)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.extract_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.extract_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.sources.isEmpty()) {
            Text(
                text = stringResource(R.string.extract_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.extract_selected, state.sources.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedButton(
            onClick = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pick_extract))
        }

        OutlinedButton(
            onClick = { scanPicker.launch(null) },
            enabled = !state.scanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_folder))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (allFilesAccess) {
                Text(
                    text = stringResource(R.string.all_files_granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { showBrowser = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.browse_folders))
                }
            } else {
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
        } else {
            Text(
                text = stringResource(R.string.all_files_android_11),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.scanning) {
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
        } else if (state.scanHits.isNotEmpty()) {
            Text(
                text = stringResource(R.string.scan_results, state.scanHits.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = viewModel::addScanHitsToExtract,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scan_add_all))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.scanHits.forEach { hit ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                                        append(
                                            stringResource(
                                                R.string.scan_entries,
                                                hit.entries,
                                            ),
                                        )
                                        if (hit.encrypted) {
                                            append(" · ")
                                            append(stringResource(R.string.scan_encrypted))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else if (state.scanError != null) {
            Text(
                text = state.scanError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.scanCompleted && state.scanHits.isEmpty()) {
            Text(
                text = stringResource(R.string.scan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { viewModel.extract() },
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

        if (state.results.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.extract_results),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.results.forEachIndexed { index, item ->
                    ExtractedRow(
                        item = item,
                        unpacking = state.unpackingIndex == index,
                        onPreview = { previewItem = item },
                        onSave = { viewModel.saveExtracted(index) },
                        onOpen = { openFile(context, item) },
                        onUnpack = { viewModel.unpackArchive(index) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    previewItem?.let { item ->
        PreviewDialog(item = item, onDismiss = { previewItem = null })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractedRow(
    item: ExtractItem,
    unpacking: Boolean,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onUnpack: () -> Unit,
) {
    val image = !item.isDirectory && isImageMime(item.mime, item.name)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        item.isDirectory -> Icons.Outlined.Folder
                        image -> Icons.Outlined.PhotoLibrary
                        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (item.isDirectory) {
                            stringResource(R.string.directory_badge)
                        } else if (item.relativePath != item.name) {
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
                if (!item.isDirectory) {
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
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.Center,
            ) {
                if (item.isDirectory) {
                    Text(
                        text = stringResource(R.string.directory_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    if (image) {
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
                        TextButton(onClick = onOpen) {
                            Text(stringResource(R.string.open))
                        }
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
}

@Composable
private fun PreviewDialog(item: ExtractItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                @Suppress("ProduceStateDoesNotAssignValue") // value is assigned below
                val bitmap by produceState<Bitmap?>(null, item.bytes) {
                    val loaded = withContext(Dispatchers.IO) {
                        runCatching {
                            BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                        }.getOrNull()
                    }
                    value = loaded
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done))
                }
            }
        }
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

private fun openFile(context: Context, item: ExtractItem) {
    val uri = item.savedUri ?: return
    val mime = item.mime ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.msg_open_failed, Toast.LENGTH_SHORT).show()
    }
}
