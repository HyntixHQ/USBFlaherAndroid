package com.hyntix.android.usbflasher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Database
import com.adamglin.phosphoricons.regular.WarningCircle
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.FlashProgress
import com.hyntix.android.usbflasher.data.FlashState
import com.hyntix.android.usbflasher.data.ImageFileInfo
import com.hyntix.android.usbflasher.data.PersistenceOutcome
import com.hyntix.lib.androidusbflasher.PersistenceActivation
import com.hyntix.lib.androidusbflasher.PersistenceProfile


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCard(
    title: String,
    value: String,
    icon: ImageVector,
    subtitle: String? = null,
    description: String? = null,
    isWarning: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (description != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isWarning) 
                            MaterialTheme.colorScheme.tertiaryContainer 
                        else 
                            MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Optional: Icon for description
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isWarning)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FlashingSheet(
    state: FlashState,
    onCancel: () -> Unit,
    onRetryPersistence: () -> Unit
) {
    val progress = when (state) {
        is FlashState.Flashing -> state.progress
        is FlashState.Verifying -> state.progress
        is FlashState.Persisting -> state.progress
        is FlashState.Cancelling -> state.progress
        is FlashState.Success -> FlashProgress(100, 100)
        else -> return
    }

    val isSuccess = state is FlashState.Success
    val isCancelling = state is FlashState.Cancelling
    val persistenceOutcome = (state as? FlashState.Success)?.persistence
    val persistenceFailed = persistenceOutcome is PersistenceOutcome.Failed
    val flashingState = state as? FlashState.Flashing
    val currentFile = flashingState?.currentFile
    val copyingFile = currentFile?.takeIf {
        flashingState.status.equals("Flashing", ignoreCase = true) && it.isNotBlank()
    }

    val title = when {
        isCancelling -> stringResource(R.string.sheet_title_cancelling)
        state is FlashState.Success -> when (persistenceOutcome) {
            is PersistenceOutcome.Enabled -> stringResource(R.string.persistence_success_title)
            is PersistenceOutcome.Failed -> stringResource(R.string.persistence_partial_title)
            null -> stringResource(R.string.sheet_title_complete)
        }
        state is FlashState.Persisting -> persistenceStageTitle(state.status)
        state is FlashState.Verifying -> stringResource(R.string.sheet_title_verifying)
        state is FlashState.Flashing -> when {
            state.status.equals("Formatting", ignoreCase = true) ->
                stringResource(R.string.stage_formatting)
            state.status.equals("Finalizing", ignoreCase = true) ->
                stringResource(R.string.stage_finalizing)
            state.status.equals("Validating", ignoreCase = true) ->
                stringResource(R.string.stage_validating)
            state.status.equals("Flashing", ignoreCase = true) &&
                !currentFile.isNullOrBlank() ->
                stringResource(R.string.stage_copying)
            else -> stringResource(R.string.stage_writing_image)
        }
        else -> stringResource(R.string.sheet_title_flashing)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            if (isSuccess) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (persistenceFailed) {
                            PhosphorIcons.Regular.WarningCircle
                        } else {
                            PhosphorIcons.Regular.CheckCircle
                        },
                        contentDescription = null,
                        tint = if (persistenceFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    when (persistenceOutcome) {
                        is PersistenceOutcome.Enabled -> {
                            Text(
                                text = stringResource(
                                    R.string.persistence_success_message,
                                    persistenceOutcome.sizeFormatted,
                                    persistenceOutcome.label
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            if (persistenceOutcome.reusedExisting) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.persistence_reused),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (persistenceOutcome.activation == PersistenceActivation.MANUAL) {
                                Spacer(modifier = Modifier.height(12.dp))
                                ManualActivationCard(state.imageFile)
                            }
                        }
                        is PersistenceOutcome.Failed -> Text(
                            text = stringResource(
                                R.string.persistence_partial_message,
                                persistenceOutcome.message
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        null -> Text(
                            text = stringResource(R.string.sheet_success_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (persistenceFailed) {
                    FilledTonalButton(
                        onClick = onRetryPersistence,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(stringResource(R.string.persistence_retry))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )

                if (copyingFile != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = copyingFile,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (isCancelling) {
                    Text(
                        text = stringResource(R.string.sheet_cancelling_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                    )
                } else if (state is FlashState.Persisting) {
                    Text(
                        text = stringResource(R.string.persistence_progress_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Only formatting reports real metadata bytes; the other
                    // persistence stages are step markers, so keep them
                    // indeterminate instead of faking a percentage.
                    if (state.progress.totalBytes > 1) {
                        val targetProgress =
                            (state.progress.percentage / 100f).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { targetProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            strokeCap = StrokeCap.Round,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.progress.bytesProgressFormatted} · " +
                                "${(targetProgress * 100).toInt().coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            strokeCap = StrokeCap.Round,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        )
                    }
                } else {
                    Text(
                        text = progress.bytesProgressFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val targetProgress = (progress.percentage / 100f).coerceIn(0f, 1f)

                    // Critically damped: the displayed value can never overshoot
                    // the last real hardware position. The stiffness is tuned so
                    // the bar keeps moving continuously between 4 MB SCSI
                    // completion updates instead of stopping and starting.
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = spring(
                            stiffness = 1200f,
                            dampingRatio = 1f,
                        ),
                        label = "progressAnimation"
                    )

                    // Hard guarantee: never display more progress than the device
                    // has actually written, even for a single frame.
                    val clampedProgress = animatedProgress.coerceIn(0f, targetProgress)

                    LinearProgressIndicator(
                        progress = { clampedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${(clampedProgress * 100).toInt().coerceIn(0, 100)}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = progress.speedFormatted,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = if (progress.speedBytesPerSecond > 0) {
                                stringResource(R.string.eta_prefix, progress.etaFormatted)
                            } else {
                                stringResource(R.string.eta_pending)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            Button(
                onClick = onCancel,
                enabled = !isCancelling,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (isSuccess) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(
                    when {
                        isCancelling -> stringResource(R.string.btn_cancelling)
                        isSuccess -> stringResource(R.string.btn_done)
                        else -> stringResource(R.string.btn_cancel)
                    }
                )
            }
        }
    }
}

/** Human-readable label for a persistence stage reported by the native layer. */
@Composable
private fun persistenceStageTitle(status: String): String = when {
    status.equals("Formatting", ignoreCase = true) ->
        stringResource(R.string.persistence_progress_formatting)
    status.equals("Configuring", ignoreCase = true) ->
        stringResource(R.string.persistence_progress_configuring)
    status.equals("ActivatingBoot", ignoreCase = true) ->
        stringResource(R.string.persistence_progress_activating)
    status.equals("Partitioning", ignoreCase = true) ->
        stringResource(R.string.persistence_progress_creating)
    status.equals("Verifying", ignoreCase = true) ->
        stringResource(R.string.persistence_progress_verifying)
    else -> stringResource(R.string.persistence_progress_planning)
}

@Composable
private fun ManualActivationCard(image: ImageFileInfo) {
    val steps = if (image.persistence?.profile == PersistenceProfile.DEBIAN_LIVE) {
        stringResource(R.string.persistence_manual_debian_steps)
    } else {
        stringResource(R.string.persistence_manual_casper_steps)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.persistence_manual_activation),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = steps,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Opt-in persistence controls shown below the image card for supported Linux
 * live images. Free-tail sizing is only offered once a target drive is known.
 */
@Composable
fun PersistenceCard(
    image: ImageFileInfo,
    maxBytes: Long,
    enabled: Boolean,
    deviceSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    onSizeSelected: (Long) -> Unit
) {
    val support = image.persistenceSupport ?: return
    val selection = image.persistence
    val minBytes = support.minBytes.toLong()
    val hasRoom = maxBytes >= minBytes
    // Allow turning persistence off even when no drive is selected.
    val canToggle = enabled && (selection != null || (hasRoom && deviceSelected))
    val toggleDescription = stringResource(R.string.persistence_toggle)
    val toggleStateDescription = if (selection != null) {
        stringResource(R.string.persistence_toggle_state_on, selection.sizeFormatted)
    } else {
        stringResource(R.string.persistence_toggle_state_off)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = PhosphorIcons.Regular.Database,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.persistence_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.persistence_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = selection != null,
                    onCheckedChange = onToggle,
                    enabled = canToggle,
                    modifier = Modifier.semantics {
                        contentDescription = toggleDescription
                        stateDescription = toggleStateDescription
                    }
                )
            }

            if (selection != null) {
                Spacer(modifier = Modifier.height(16.dp))
                val automatic = support.activation != PersistenceActivation.MANUAL
                Surface(
                    color = if (automatic) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    contentColor = if (automatic) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = stringResource(
                            if (automatic) R.string.persistence_auto_activation
                            else R.string.persistence_manual_activation
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.persistence_size_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                val options = persistenceSizeOptions(minBytes, maxBytes)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { option ->
                        FilterChip(
                            selected = selection.sizeBytes == option.bytes,
                            onClick = { onSizeSelected(option.bytes) },
                            label = { Text(option.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.persistence_not_encrypted
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!automatic) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ManualActivationCard(image)
                }
            } else if (deviceSelected && !hasRoom) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.persistence_no_space),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private data class PersistenceSizeOption(val bytes: Long, val label: String)

@Composable
private fun persistenceSizeOptions(minBytes: Long, maxBytes: Long): List<PersistenceSizeOption> {
    val options = mutableListOf<PersistenceSizeOption>()
    for (gib in PERSISTENCE_CHIP_GIB) {
        val bytes = gib * 1024L * 1024L * 1024L
        if (bytes in minBytes..maxBytes) {
            options += PersistenceSizeOption(
                bytes = bytes,
                label = stringResource(R.string.persistence_size_gb, gib)
            )
        }
    }
    if (maxBytes >= minBytes && options.none { it.bytes == maxBytes }) {
        options += PersistenceSizeOption(
            bytes = maxBytes,
            label = stringResource(R.string.persistence_size_max)
        )
    }
    return options
}

/** Size presets offered by the persistence card, in GiB. */
private val PERSISTENCE_CHIP_GIB = listOf(1L, 2L, 4L, 8L, 16L)
