package com.imagefolder.open.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagefolder.open.MAX_PACK_BYTES
import com.imagefolder.open.MAX_PACK_MB
import com.imagefolder.open.MainViewModel
import com.imagefolder.open.PackUiState
import com.imagefolder.open.R
import com.imagefolder.open.core.ImageProcessor
import com.imagefolder.open.data.PickedFile
import com.imagefolder.open.data.PickedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PackScreen(
    state: PackUiState,
    compatMode: Boolean,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setCover(uri)
    }
    val payloadPickerMultiple =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.addPayloads(uris)
        }
    val payloadPickerSingleImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.addPayloads(listOf(uri))
        }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    // Text fields keep their own editing state. Writing every keystroke through
    // the ViewModel's StateFlow made the value arrive one frame late, which
    // dropped characters and reset pinyin composition while typing a note.
    var noteText by rememberSaveable { mutableStateOf(state.note) }
    var nameText by rememberSaveable { mutableStateOf(state.outputName) }
    LaunchedEffect(state.note) { if (state.note != noteText) noteText = state.note }
    LaunchedEffect(state.outputName) { if (state.outputName != nameText) nameText = state.outputName }

    val passwordMismatch = state.passwordConfirm.isNotEmpty() && state.password != state.passwordConfirm
    val hasContent = state.payloads.isNotEmpty() || state.folders.isNotEmpty()
    val passwordReady = !state.encrypt ||
        (state.password.isNotEmpty() && state.password == state.passwordConfirm)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.cover_section),
            style = MaterialTheme.typography.titleMedium,
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val cover = state.cover
                @Suppress("ProduceStateDoesNotAssignValue") // value is assigned below
                val coverBitmap by produceState<Bitmap?>(null, cover?.uri) {
                    val uri = cover?.uri
                    value = if (uri == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            // Preview only: decode a downsampled bitmap instead of
                            // the full-resolution photo (which could be 200 MB).
                            ImageProcessor.decodePreview(context, uri, PREVIEW_MAX_SIZE)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = coverBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = cover?.name ?: stringResource(R.string.cover_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }) {
                        Text(stringResource(R.string.pick_cover))
                    }
                    if (state.cover != null) {
                        OutlinedButton(onClick = { viewModel.setCover(null) }) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.payload_section),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.payloads.isEmpty()) {
            Text(
                text = stringResource(R.string.payload_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.payloads.forEach { file ->
                    PayloadRow(file = file, onRemove = { viewModel.removePayload(file.uri) })
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (compatMode) {
                    payloadPickerSingleImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } else {
                    payloadPickerMultiple.launch(arrayOf("*/*"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (compatMode) R.string.pick_payload_compat else R.string.pick_payloads,
                ),
            )
        }

        Text(
            text = stringResource(R.string.folder_section),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.folders.isEmpty()) {
            Text(
                text = stringResource(R.string.folder_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.folders.forEach { folder ->
                    FolderRow(folder = folder, onRemove = { viewModel.removeFolder(folder.uri) })
                }
            }
        }

        if (state.folderLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.folder_reading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            enabled = !state.folderLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pick_folder))
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.options_section),
            style = MaterialTheme.typography.titleMedium,
        )
        OptionSwitchRow(
            icon = Icons.Outlined.FolderZip,
            title = stringResource(R.string.compress_title),
            summary = stringResource(R.string.compress_summary),
            checked = state.compress,
            onCheckedChange = viewModel::setCompress,
        )
        OptionSwitchRow(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.encrypt_title),
            summary = stringResource(R.string.encrypt_summary),
            checked = state.encrypt,
            onCheckedChange = viewModel::setEncrypt,
        )

        if (state.encrypt) {
            PasswordField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = stringResource(R.string.password_label),
            )
            PasswordField(
                value = state.passwordConfirm,
                onValueChange = viewModel::setPasswordConfirm,
                label = stringResource(R.string.password_confirm_label),
            )
            if (passwordMismatch) {
                Text(
                    text = stringResource(R.string.password_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.password_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.advanced_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = nameText,
            onValueChange = {
                nameText = it
                viewModel.setOutputName(it)
            },
            label = { Text(stringResource(R.string.output_name_label)) },
            placeholder = { Text(stringResource(R.string.output_name_placeholder)) },
            supportingText = { Text(stringResource(R.string.output_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = noteText,
            onValueChange = {
                noteText = it
                viewModel.setNote(it)
            },
            label = { Text(stringResource(R.string.note_label)) },
            supportingText = { Text(stringResource(R.string.note_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        val knownBytes = state.payloads.sumOf { maxOf(0L, it.size) }
        if (state.payloads.isNotEmpty() || state.folders.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.pack_size_hint,
                    formatSize(knownBytes),
                    MAX_PACK_MB,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (knownBytes > MAX_PACK_BYTES) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Button(
            onClick = viewModel::generate,
            enabled = hasContent && passwordReady && !state.generating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.generating))
            } else {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.generate))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Preview decode target: 1080 px covers a 180 dp box on a 3x density screen. */
private const val PREVIEW_MAX_SIZE = 1080

@Composable
private fun PayloadRow(file: PickedFile, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.remove),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(folder: PickedFolder, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.folder_file_count, folder.fileCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.remove),
                )
            }
        }
    }
}

@Composable
private fun OptionSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    var visible by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.hide_password else R.string.show_password,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
