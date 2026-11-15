package com.imagefolder.open.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imagefolder.open.MainViewModel
import com.imagefolder.open.R

private data class NavItem(val labelRes: Int, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageFolderApp(viewModel: MainViewModel = viewModel()) {
    val packState by viewModel.pack.collectAsState()
    val extractState by viewModel.extract.collectAsState()
    val compatMode by viewModel.compatMode.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val languageMode by viewModel.languageMode.collectAsState()
    val message by viewModel.message.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val pendingShare by viewModel.pendingShare.collectAsState()
    val shareWarningSeen by viewModel.shareWarningSeen.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    pendingShare?.let { pending ->
        ShareChoiceDialog(
            count = pending.extractSources.size,
            onExtract = viewModel::confirmShareExtract,
            onPack = viewModel::confirmSharePack,
            onDismiss = viewModel::dismissPendingShare,
        )
    }

    val result = packState.result
    if (result != null) {
        GeneratedImageScreen(
            result = result,
            onSave = viewModel::saveResult,
            onBack = viewModel::clearResult,
            snackbarHostState = snackbarHostState,
            shareWarningSeen = shareWarningSeen,
            onShareWarningSeen = viewModel::markShareWarningSeen,
        )
        return
    }

    val tabs = listOf(
        NavItem(R.string.tab_pack, Icons.Outlined.AddPhotoAlternate),
        NavItem(R.string.tab_extract, Icons.Outlined.Unarchive),
        NavItem(R.string.tab_about, Icons.Outlined.Info),
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = activeTab == index,
                        onClick = { viewModel.selectTab(index) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (activeTab) {
                0 -> PackScreen(state = packState, compatMode = compatMode, viewModel = viewModel)
                1 -> ExtractScreen(state = extractState, viewModel = viewModel)
                else -> AboutScreen(
                    compatMode = compatMode,
                    onCompatModeChange = viewModel::setCompatMode,
                    themeMode = themeMode,
                    onThemeModeChange = viewModel::setThemeMode,
                    languageMode = languageMode,
                    onLanguageModeChange = viewModel::setLanguageMode,
                )
            }
        }
    }
}

@Composable
private fun ShareChoiceDialog(
    count: Int,
    onExtract: () -> Unit,
    onPack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_choice_title)) },
        text = { Text(stringResource(R.string.share_choice_message, count)) },
        confirmButton = {
            TextButton(onClick = onExtract) {
                Text(stringResource(R.string.share_extract_action))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPack) {
                    Text(stringResource(R.string.share_pack_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
