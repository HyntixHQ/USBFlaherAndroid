package com.hyntix.android.usbflasher.ui

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.X
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.download.DownloadError
import com.hyntix.android.usbflasher.download.DownloadErrorCode
import com.hyntix.android.usbflasher.download.DownloadFormatters
import com.hyntix.android.usbflasher.download.DownloadRecord
import com.hyntix.android.usbflasher.download.DownloadStatus
import com.hyntix.android.usbflasher.download.DownloadStrings
import androidx.compose.ui.semantics.ProgressBarRangeInfo

@Composable
fun downloadStatusText(record: DownloadRecord): String {
    val context = LocalContext.current
    return remember(record.status, record.pauseReason, record.error) {
        DownloadStrings.statusText(context, record)
    }
}

@Composable
fun DownloadStatusCard(
    record: DownloadRecord,
    onClick: () -> Unit,
    onUseIso: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val ready = record.isReady
    val active = record.status.isActive
    var confirmDelete by remember { mutableStateOf(false) }
    val progress = record.percentage
    val progressDescription = stringResource(R.string.download_cd_progress)
    val bytesText = stringResource(
        R.string.download_progress_text,
        (progress * 100).toInt().coerceIn(0, 100),
        DownloadFormatters.bytes(context, record.bytesDownloaded),
        DownloadFormatters.bytes(context, record.totalBytes)
    )
    val speedText = if (record.speedBytesPerSecond > 0) {
        DownloadFormatters.speed(context, record.speedBytesPerSecond)
    } else {
        null
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) {
                        PhosphorIcons.Regular.CheckCircle
                    } else {
                        PhosphorIcons.Regular.DownloadSimple
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ready) {
                            stringResource(R.string.download_card_ready, record.fileName)
                        } else {
                            record.displayName
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = downloadStatusText(record),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (ready) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (!ready && (active || record.status == DownloadStatus.FAILED)) {
                if (record.status == DownloadStatus.QUEUED ||
                    record.status == DownloadStatus.RESOLVING
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .semantics {
                                contentDescription = progressDescription
                                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                            }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = bytesText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (speedText != null) {
                        Text(
                            text = speedText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (ready && onUseIso != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onUseIso,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.download_use_iso))
                    }
                    if (onDelete != null) {
                        OutlinedButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.download_delete))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.download_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.download_delete_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.download_delete_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun DownloadProgressSheet(
    record: DownloadRecord,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onUseIso: () -> Unit
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationsEnabled = remember {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val progressDescription = stringResource(R.string.download_cd_progress)
    val status = downloadStatusText(record)
    val progress = record.percentage
    val showProgress = record.status.isActive || record.status == DownloadStatus.FAILED
    val canResume = record.status == DownloadStatus.PAUSED ||
        (record.status == DownloadStatus.FAILED && canRetry(record))
    val canPause = (record.status == DownloadStatus.DOWNLOADING && record.rangeSupported) ||
        record.status == DownloadStatus.WAITING_FOR_NETWORK
    val bytesText = stringResource(
        R.string.download_progress_text,
        (progress * 100).toInt().coerceIn(0, 100),
        DownloadFormatters.bytes(context, record.bytesDownloaded),
        DownloadFormatters.bytes(context, record.totalBytes)
    )
    val hasSpeed = record.speedBytesPerSecond > 0
    val speedText = if (hasSpeed) {
        DownloadFormatters.speed(context, record.speedBytesPerSecond)
    } else {
        null
    }
    val etaText = if (hasSpeed) {
        stringResource(R.string.download_eta, DownloadFormatters.eta(context, record.etaSeconds))
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = record.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = PhosphorIcons.Regular.X,
                            contentDescription = stringResource(R.string.download_close)
                        )
                    }
                }

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (record.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )

                if (showProgress) {
                    if (record.status == DownloadStatus.QUEUED ||
                        record.status == DownloadStatus.RESOLVING
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .semantics {
                                    contentDescription = progressDescription
                                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                                }
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                bytesText,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (speedText != null) {
                                Text(
                                    speedText,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (etaText != null) {
                            Text(
                                etaText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (record.status == DownloadStatus.COMPLETED && record.isReady) {
                    Text(
                        text = stringResource(R.string.download_completed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (record.status.isActive &&
                    record.status != DownloadStatus.PAUSED
                ) {
                    Text(
                        text = stringResource(R.string.download_leave_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (record.status.isActive && !notificationsEnabled) {
                    Text(
                        text = stringResource(R.string.download_notifications_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.status == DownloadStatus.DOWNLOADING && !record.rangeSupported) {
                    Text(
                        text = stringResource(R.string.download_range_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(4.dp))

                when {
                    record.status == DownloadStatus.COMPLETED && record.isReady -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onUseIso,
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) {
                                Text(stringResource(R.string.download_use_iso))
                            }
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text(stringResource(R.string.download_delete))
                            }
                        }
                    }

                    canResume -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onResume,
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) {
                                Icon(
                                    imageVector = PhosphorIcons.Regular.Play,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (record.rangeSupported) {
                                            R.string.download_resume
                                        } else {
                                            R.string.download_restart
                                        }
                                    )
                                )
                            }
                            OutlinedButton(
                                onClick = { confirmCancel = true },
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text(stringResource(R.string.download_cancel))
                            }
                        }
                    }

                    canPause -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onPause,
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) {
                                Icon(
                                    imageVector = PhosphorIcons.Regular.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.download_pause))
                            }
                            OutlinedButton(
                                onClick = { confirmCancel = true },
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text(stringResource(R.string.download_cancel))
                            }
                        }
                    }

                    record.status == DownloadStatus.FAILED -> {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text(stringResource(R.string.download_delete))
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = { confirmCancel = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text(stringResource(R.string.download_cancel))
                        }
                    }
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.download_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.download_cancel_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.download_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.download_keep_file))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.download_delete_confirm_title)) },
            text = { Text(stringResource(R.string.download_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.download_delete_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun DownloadErrorDialog(error: DownloadError, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_error_title)) },
        text = { Text(DownloadStrings.errorMessage(context, error)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_ok))
            }
        }
    )
}

@Composable
fun MeteredDownloadDialog(
    prompt: MeteredDownloadPrompt,
    onAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_metered_title)) },
        text = {
            Text(
                stringResource(
                    R.string.download_metered_body,
                    prompt.displayName,
                    DownloadFormatters.bytes(context, prompt.sizeBytes)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.download_metered_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.download_metered_wait))
            }
        }
    )
}

private fun canRetry(record: DownloadRecord): Boolean {
    val code = record.error?.code ?: return true
    return code !in setOf(
        DownloadErrorCode.CHECKSUM_MISMATCH,
        DownloadErrorCode.CHECKSUM_UNAVAILABLE,
        DownloadErrorCode.CHECKSUM_TABLE_INVALID,
        DownloadErrorCode.FILE_CHANGED,
        DownloadErrorCode.INVALID_FILE,
        DownloadErrorCode.DESTINATION_UNAVAILABLE,
        DownloadErrorCode.DESTINATION_UNSUPPORTED
    )
}
