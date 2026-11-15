package com.imagefolder.open.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagefolder.open.GeneratedResult
import com.imagefolder.open.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratedImageScreen(
    result: GeneratedResult,
    onSave: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    shareWarningSeen: Boolean,
    onShareWarningSeen: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showShareWarning by remember { mutableStateOf(false) }
    var dontAskAgain by remember { mutableStateOf(false) }

    fun share() {
        scope.launch {
            runCatching { shareGeneratedImage(context, result) }
                .onFailure {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.msg_share_failed,
                            it.message ?: it.javaClass.simpleName,
                        ),
                    )
                }
        }
    }

    if (showShareWarning) {
        AlertDialog(
            onDismissRequest = {
                dontAskAgain = false
                showShareWarning = false
            },
            title = { Text(stringResource(R.string.share_warning_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.share_warning_message))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it },
                        )
                        Text(stringResource(R.string.share_warning_dont_ask))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontAskAgain) onShareWarningSeen()
                    showShareWarning = false
                    share()
                }) {
                    Text(stringResource(R.string.share_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dontAskAgain = false
                    showShareWarning = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.generated_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            @Suppress("ProduceStateDoesNotAssignValue") // value is assigned below
            val bitmap by produceState<Bitmap?>(null, result.bytes) {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                    }.getOrNull()
                }
                value = loaded
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                val preview = bitmap
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Text(result.fileName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatSize(result.bytes.size.toLong()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.bundle) {
                Text(
                    text = stringResource(R.string.generated_entries, result.entryCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val location = result.savedLocation
            if (result.saved && location != null) {
                Text(
                    text = stringResource(R.string.generated_saved, location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = onSave,
                enabled = !result.saved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_to_gallery))
            }

            OutlinedButton(
                onClick = {
                    if (shareWarningSeen) {
                        share()
                    } else {
                        dontAskAgain = false
                        showShareWarning = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_action))
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.done))
            }
        }
    }
}
