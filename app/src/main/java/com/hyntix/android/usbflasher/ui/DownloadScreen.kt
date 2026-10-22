package com.hyntix.android.usbflasher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Globe
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.download.DownloadFormatters
import com.hyntix.android.usbflasher.download.DownloadRecord
import com.hyntix.android.usbflasher.download.DownloadStatus
import com.hyntix.android.usbflasher.download.LinuxIsoEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel,
    downloadsEnabled: Boolean,
    onBack: () -> Unit,
    onRequestDestination: (LinuxIsoEntry) -> Unit
) {
    val catalog by viewModel.catalogState.collectAsState()
    val records by viewModel.records.collectAsState()
    val startingEntryId by viewModel.startingEntryId.collectAsState()
    val focusRecordId by viewModel.focusRecordId.collectAsState()
    val uiError by viewModel.uiError.collectAsState()
    val meteredPrompt by viewModel.meteredPrompt.collectAsState()

    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    val selectedRecord = records.firstOrNull { it.id == selectedRecordId }
    val activeRecords = records.filter {
        it.status.isActive || it.isReady || it.status == DownloadStatus.FAILED
    }
    val hasActive = records.any { it.status.isActive }

    LaunchedEffect(focusRecordId) {
        if (focusRecordId != null) {
            selectedRecordId = focusRecordId
        }
    }

    val selectedRecordMissing = selectedRecordId != null && selectedRecord == null
    LaunchedEffect(selectedRecordMissing) {
        if (selectedRecordMissing) {
            selectedRecordId = null
            viewModel.dismissFocus()
        }
    }

    BackHandler {
        if (selectedRecordId != null) {
            selectedRecordId = null
            viewModel.dismissFocus()
        } else {
            onBack()
        }
    }

    if (uiError != null) {
        DownloadErrorDialog(
            error = uiError!!,
            onDismiss = viewModel::dismissError
        )
    }

    if (meteredPrompt != null) {
        MeteredDownloadDialog(
            prompt = meteredPrompt!!,
            onAllow = viewModel::confirmMeteredDownload,
            onDismiss = viewModel::dismissMeteredPrompt
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.download_title),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = PhosphorIcons.Regular.ArrowLeft,
                                contentDescription = stringResource(R.string.download_cd_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.download_linux_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                Text(
                    text = stringResource(R.string.download_verified_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (!downloadsEnabled) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.download_disabled_flashing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (activeRecords.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.download_active_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(activeRecords, key = { it.id }) { record ->
                    DownloadStatusCard(
                        record = record,
                        onClick = {
                            selectedRecordId = record.id
                            viewModel.focus(record.id)
                        },
                        onUseIso = if (record.isReady) {
                            {
                                viewModel.useIso(record.id)
                                onBack()
                            }
                        } else {
                            null
                        },
                        onDelete = if (record.isReady) {
                            { viewModel.delete(record.id) }
                        } else {
                            null
                        }
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.download_linux_tab),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (downloadsEnabled && hasActive) {
                item {
                    Text(
                        text = stringResource(R.string.download_active_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                catalog.loading -> {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.download_catalog_loading))
                        }
                    }
                }

                catalog.failed -> {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.download_catalog_error),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = viewModel::loadCatalog) {
                                Text(stringResource(R.string.download_retry))
                            }
                        }
                    }
                }

                catalog.entries.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.download_catalog_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    items(catalog.entries, key = { it.id }) { entry ->
                        LinuxIsoCard(
                            entry = entry,
                            enabled = downloadsEnabled && !hasActive &&
                                startingEntryId == null,
                            starting = startingEntryId == entry.id,
                            onDownload = { onRequestDestination(entry) }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (selectedRecord != null) {
        DownloadProgressSheet(
            record = selectedRecord,
            onDismiss = {
                selectedRecordId = null
                viewModel.dismissFocus()
            },
            onPause = { viewModel.pause(selectedRecord.id) },
            onResume = { viewModel.resume(selectedRecord.id) },
            onCancel = {
                viewModel.cancel(selectedRecord.id)
                selectedRecordId = null
                viewModel.dismissFocus()
            },
            onDelete = {
                viewModel.delete(selectedRecord.id)
                selectedRecordId = null
                viewModel.dismissFocus()
            },
            onUseIso = {
                viewModel.useIso(selectedRecord.id)
                selectedRecordId = null
                viewModel.dismissFocus()
                onBack()
            }
        )
    }
}

@Composable
private fun LinuxIsoCard(
    entry: LinuxIsoEntry,
    enabled: Boolean,
    starting: Boolean,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = PhosphorIcons.Regular.DownloadSimple,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${linuxEditionLabel(entry)} · ${entry.arch}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.download_size,
                            DownloadFormatters.bytes(context, entry.approxSizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.download_live_usb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (entry.persistenceSupported) {
                        stringResource(R.string.download_persistence_supported)
                    } else {
                        stringResource(R.string.download_persistence_unsupported)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { uriHandler.openUri(entry.homepageUrl) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.Globe,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.download_official_site))
                }
                TextButton(
                    onClick = { uriHandler.openUri(entry.licenseUrl) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) {
                    Text(stringResource(R.string.download_license))
                }
            }

            Button(
                onClick = onDownload,
                enabled = enabled || starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.download_preparing))
                } else {
                    Icon(
                        imageVector = PhosphorIcons.Regular.DownloadSimple,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.download_download_iso))
                }
            }

            Text(
                text = stringResource(R.string.download_select_destination_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
