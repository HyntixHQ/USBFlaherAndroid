package com.hyntix.android.usbflasher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.FlashProgress
import com.hyntix.android.usbflasher.data.FlashState


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
    onCancel: () -> Unit
) {
    val progress = when (state) {
        is FlashState.Flashing -> state.progress
        is FlashState.Verifying -> state.progress
        is FlashState.Cancelling -> state.progress
        is FlashState.Success -> FlashProgress(100, 100)
        else -> return
    }

    val isSuccess = state is FlashState.Success
    val isCancelling = state is FlashState.Cancelling
    val currentFile = (state as? FlashState.Flashing)?.currentFile
    val copyingFile = currentFile?.takeIf {
        state is FlashState.Flashing &&
            state.status.equals("Flashing", ignoreCase = true) &&
            it.isNotBlank()
    }

    val title = when {
        isCancelling -> stringResource(R.string.sheet_title_cancelling)
        isSuccess -> stringResource(R.string.sheet_title_complete)
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
        ) {
            if (isSuccess) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Regular.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.sheet_success_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(clampedProgress * 100).toInt().coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = progress.speedFormatted,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = if (progress.speedBytesPerSecond > 0) {
                                stringResource(R.string.eta_prefix, progress.etaFormatted)
                            } else {
                                stringResource(R.string.eta_pending)
                            },
                            style = MaterialTheme.typography.labelMedium
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
