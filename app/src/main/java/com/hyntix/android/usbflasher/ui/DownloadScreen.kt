package com.hyntix.android.usbflasher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Globe
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.download.DownloadFormatters
import com.hyntix.android.usbflasher.download.DownloadRecord
import com.hyntix.android.usbflasher.download.DownloadStatus
import com.hyntix.android.usbflasher.download.LinuxCategory
import com.hyntix.android.usbflasher.download.LinuxDistribution
import com.hyntix.android.usbflasher.download.LinuxIsoEntry
import com.hyntix.android.usbflasher.download.PersistenceInfo
import com.hyntix.android.usbflasher.download.PersistenceSupport
import kotlinx.serialization.Serializable

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

    var selectedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    val backStack = rememberDownloadBackStack()
    val currentKey = backStack.lastOrNull()
    val currentDistribution = (currentKey as? DistributionKey)?.let { key ->
        catalog.distributions.firstOrNull { it.id == key.distributionId }
    }
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

    LaunchedEffect(catalog.loading, catalog.distributions) {
        if (!catalog.loading) {
            while (true) {
                val top = backStack.lastOrNull() as? DistributionKey ?: break
                if (catalog.distributions.any { it.id == top.distributionId }) break
                backStack.removeLastOrNull()
            }
        }
    }

    val selectedRecordMissing = selectedRecordId != null && selectedRecord == null
    LaunchedEffect(selectedRecordMissing) {
        if (selectedRecordMissing) {
            selectedRecordId = null
            viewModel.dismissFocus()
        }
    }

    BackHandler(enabled = selectedRecordId != null) {
        selectedRecordId = null
        viewModel.dismissFocus()
    }
    BackHandler(enabled = selectedRecordId == null && backStack.size == 1) {
        onBack()
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
                            currentDistribution?.name
                                ?: stringResource(R.string.download_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                } else {
                                    onBack()
                                }
                            }
                        ) {
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
        // NavDisplay's default SaveableStateHolder decorator keeps the catalog's
        // LazyListState while the detail entry is on top and removes the detail's
        // saved state when that entry is popped, so a new detail starts at the top.
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            entryProvider = entryProvider {
                entry<DownloadCatalogKey> {
                    DownloadCatalogContent(
                        catalog = catalog,
                        activeRecords = activeRecords,
                        downloadsEnabled = downloadsEnabled,
                        hasActive = hasActive,
                        onRetryCatalog = viewModel::loadCatalog,
                        onRecordClick = { record ->
                            selectedRecordId = record.id
                            viewModel.focus(record.id)
                        },
                        onUseRecord = { record ->
                            viewModel.useIso(record.id)
                            onBack()
                        },
                        onDeleteRecord = { record -> viewModel.delete(record.id) },
                        onOpenDistribution = { distribution ->
                            if (backStack.lastOrNull() == DownloadCatalogKey) {
                                backStack.add(DistributionKey(distribution.id))
                            }
                        }
                    )
                }
                entry<DistributionKey> { key ->
                    DownloadDistributionContent(
                        distribution = catalog.distributions.firstOrNull {
                            it.id == key.distributionId
                        },
                        catalogLoading = catalog.loading,
                        catalogFailed = catalog.failed,
                        activeRecords = activeRecords,
                        downloadsEnabled = downloadsEnabled,
                        hasActive = hasActive,
                        startingEntryId = startingEntryId,
                        onRetryCatalog = viewModel::loadCatalog,
                        onRecordClick = { record ->
                            selectedRecordId = record.id
                            viewModel.focus(record.id)
                        },
                        onUseRecord = { record ->
                            viewModel.useIso(record.id)
                            onBack()
                        },
                        onDeleteRecord = { record -> viewModel.delete(record.id) },
                        onDownload = onRequestDestination
                    )
                }
            }
        )
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

@Serializable
private sealed interface DownloadNavKey : NavKey

@Serializable
private data object DownloadCatalogKey : DownloadNavKey

@Serializable
private data class DistributionKey(val distributionId: String) : DownloadNavKey

@Composable
private fun rememberDownloadBackStack(): NavBackStack<DownloadNavKey> {
    // Typed, serializable back stack following Navigation 3's save-state pattern.
    return rememberSerializable(serializer = NavBackStackSerializer<DownloadNavKey>()) {
        NavBackStack<DownloadNavKey>(DownloadCatalogKey)
    }
}

@Composable
private fun DownloadCatalogContent(
    catalog: DownloadCatalogUiState,
    activeRecords: List<DownloadRecord>,
    downloadsEnabled: Boolean,
    hasActive: Boolean,
    onRetryCatalog: () -> Unit,
    onRecordClick: (DownloadRecord) -> Unit,
    onUseRecord: (DownloadRecord) -> Unit,
    onDeleteRecord: (DownloadRecord) -> Unit,
    onOpenDistribution: (LinuxDistribution) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
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
            item { DownloadsDisabledCard() }
        }

        activeDownloadsSection(
            activeRecords = activeRecords,
            onRecordClick = onRecordClick,
            onUseRecord = onUseRecord,
            onDeleteRecord = onDeleteRecord
        )

        item {
            Text(
                text = stringResource(R.string.download_catalog_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (downloadsEnabled && hasActive) {
            item { ActiveDownloadNote() }
        }

        when {
            catalog.loading -> {
                item { CatalogLoadingRow() }
            }

            catalog.failed -> {
                item { CatalogError(onRetry = onRetryCatalog) }
            }

            catalog.distributions.isEmpty() -> {
                item {
                    Text(
                        text = stringResource(R.string.download_catalog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                catalog.categories.forEach { category ->
                    val distributions = catalog.distributions.filter {
                        it.categoryId == category.id
                    }
                    if (distributions.isNotEmpty()) {
                        item(key = "category-${category.id}") {
                            CategoryHeader(category)
                        }
                        items(distributions, key = { "distribution-${it.id}" }) { distro ->
                            DistributionCard(
                                distribution = distro,
                                onOpen = { onOpenDistribution(distro) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DownloadDistributionContent(
    distribution: LinuxDistribution?,
    catalogLoading: Boolean,
    catalogFailed: Boolean,
    activeRecords: List<DownloadRecord>,
    downloadsEnabled: Boolean,
    hasActive: Boolean,
    startingEntryId: String?,
    onRetryCatalog: () -> Unit,
    onRecordClick: (DownloadRecord) -> Unit,
    onUseRecord: (DownloadRecord) -> Unit,
    onDeleteRecord: (DownloadRecord) -> Unit,
    onDownload: (LinuxIsoEntry) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!downloadsEnabled) {
            item { DownloadsDisabledCard() }
        }

        activeDownloadsSection(
            activeRecords = activeRecords,
            onRecordClick = onRecordClick,
            onUseRecord = onUseRecord,
            onDeleteRecord = onDeleteRecord
        )

        when {
            distribution != null -> {
                item {
                    DistributionHeader(distribution = distribution)
                }
                if (downloadsEnabled && hasActive) {
                    item { ActiveDownloadNote() }
                }
                item {
                    Text(
                        text = stringResource(R.string.download_variants_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(distribution.variants, key = { it.id }) { entry ->
                    LinuxIsoCard(
                        entry = entry,
                        enabled = downloadsEnabled && !hasActive &&
                            startingEntryId == null,
                        starting = startingEntryId == entry.id,
                        onDownload = { onDownload(entry) }
                    )
                }
            }

            catalogLoading -> {
                item { CatalogLoadingRow() }
            }

            catalogFailed -> {
                item { CatalogError(onRetry = onRetryCatalog) }
            }

            else -> {
                item {
                    Text(
                        text = stringResource(R.string.download_catalog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun LazyListScope.activeDownloadsSection(
    activeRecords: List<DownloadRecord>,
    onRecordClick: (DownloadRecord) -> Unit,
    onUseRecord: (DownloadRecord) -> Unit,
    onDeleteRecord: (DownloadRecord) -> Unit
) {
    if (activeRecords.isEmpty()) return

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
            onClick = { onRecordClick(record) },
            onUseIso = if (record.isReady) {
                { onUseRecord(record) }
            } else {
                null
            },
            onDelete = if (record.isReady) {
                { onDeleteRecord(record) }
            } else {
                null
            }
        )
    }
}

@Composable
private fun DownloadsDisabledCard() {
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

@Composable
private fun ActiveDownloadNote() {
    Text(
        text = stringResource(R.string.download_active_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CatalogLoadingRow() {
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

@Composable
private fun CatalogError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.download_catalog_error),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.download_retry))
        }
    }
}

@Composable
private fun CategoryHeader(category: LinuxCategory) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (category.description.isNotBlank()) {
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DistributionCard(
    distribution: LinuxDistribution,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = PhosphorIcons.Regular.Globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = distribution.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = distribution.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.download_variant_count,
                        distribution.variants.size,
                        distribution.variants.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.download_architectures,
                        distribution.architectures.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.download_view_variants),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DistributionHeader(distribution: LinuxDistribution) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = distribution.description,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(
                R.string.download_architectures,
                distribution.architectures.joinToString(", ")
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { uriHandler.openUri(distribution.homepageUrl) },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(stringResource(R.string.download_official_site))
            }
            if (!distribution.releaseNotesUrl.isNullOrBlank()) {
                TextButton(
                    onClick = { uriHandler.openUri(distribution.releaseNotesUrl) },
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(stringResource(R.string.download_release_notes))
                }
            }
            TextButton(
                onClick = { uriHandler.openUri(distribution.licenseUrl) },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(stringResource(R.string.download_license))
            }
        }
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
                        text = "${entry.variantName} · ${entry.release}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.releaseChannel} · ${entry.imageType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            VariantMeta(
                label = stringResource(R.string.download_meta_desktop),
                value = entry.desktopEnvironment
                    ?: stringResource(R.string.download_meta_not_applicable)
            )
            VariantMeta(
                label = stringResource(R.string.download_meta_architecture),
                value = entry.arch
            )
            VariantMeta(
                label = stringResource(R.string.download_meta_image),
                value = entry.imageType
            )
            VariantMeta(
                label = stringResource(R.string.download_meta_version),
                value = "${entry.release} · ${entry.releaseChannel}"
            )
            VariantMeta(
                label = stringResource(R.string.download_meta_size),
                value = stringResource(
                    R.string.download_size,
                    DownloadFormatters.bytes(context, entry.approxSizeBytes)
                )
            )

            PersistenceSummary(persistence = entry.persistence)

            Button(
                onClick = onDownload,
                enabled = enabled,
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

@Composable
private fun VariantMeta(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
private fun PersistenceSummary(persistence: PersistenceInfo) {
    val (label, color) = when (persistence.support) {
        PersistenceSupport.APP -> stringResource(R.string.download_persistence_supported) to
            MaterialTheme.colorScheme.primary
        PersistenceSupport.DISTRO -> stringResource(R.string.download_persistence_distro) to
            MaterialTheme.colorScheme.tertiary
        PersistenceSupport.NONE -> stringResource(R.string.download_persistence_unsupported) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
        if (persistence.requiresConfiguration) {
            Text(
                text = stringResource(R.string.download_persistence_config_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val notes = persistence.notes
        if (!notes.isNullOrBlank()) {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
