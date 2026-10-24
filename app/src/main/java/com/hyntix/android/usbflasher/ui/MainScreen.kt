package com.hyntix.android.usbflasher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.FlashState
import com.hyntix.android.usbflasher.data.UsbDeviceInfo
import com.hyntix.android.usbflasher.download.DownloadFormatters
import com.hyntix.android.usbflasher.download.DownloadStatus
import com.hyntix.android.usbflasher.domain.UsbBackupService
import com.hyntix.android.usbflasher.domain.UsbVerificationService
import com.hyntix.android.usbflasher.download.LinuxIsoEntry
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.Usb
import com.adamglin.phosphoricons.regular.Terminal
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.FileArrowDown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: FlashViewModel,
    downloadViewModel: DownloadViewModel,
    onSelectFile: () -> Unit,
    onRequestDownload: (LinuxIsoEntry) -> Unit,
    onRequestPermission: (UsbDeviceInfo) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val selectedImage by viewModel.selectedImageInfo.collectAsState()
    val selectedDevice by viewModel.selectedDeviceInfo.collectAsState()
    val verificationState by com.hyntix.android.usbflasher.domain.UsbOperationBus.verification.collectAsState()
    val backupState by com.hyntix.android.usbflasher.domain.UsbOperationBus.backup.collectAsState()
    val isVerifyRunning = verificationState is com.hyntix.android.usbflasher.domain.UsbVerificationState.Running
    val isBackupRunning = backupState is com.hyntix.android.usbflasher.domain.UsbBackupState.Running

    val downloadRecords by downloadViewModel.records.collectAsState()
    val downloadFocusId by downloadViewModel.focusRecordId.collectAsState()
    val downloadUiError by downloadViewModel.uiError.collectAsState()
    val downloadMeteredPrompt by downloadViewModel.meteredPrompt.collectAsState()

    var showDevicePicker by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var showDownloadScreen by rememberSaveable { mutableStateOf(false) }
    var downloadSheetId by remember { mutableStateOf<String?>(null) }

    val isFlashingNow = state is FlashState.Flashing ||
        state is FlashState.Verifying ||
        state is FlashState.Persisting ||
        state is FlashState.Cancelling

    // A download started from the home screen opens its progress sheet once
    // the destination has been selected. Downloads started from the download
    // screen are handled by DownloadScreen's own focus state.
    LaunchedEffect(showDownloadScreen, downloadFocusId) {
        if (!showDownloadScreen && downloadFocusId != null) {
            downloadSheetId = downloadFocusId
            downloadViewModel.dismissFocus()
        }
    }
    val downloadSheetMissing = downloadSheetId != null &&
        downloadRecords.none { it.id == downloadSheetId }
    LaunchedEffect(downloadSheetMissing) {
        if (downloadSheetMissing) {
            downloadSheetId = null
        }
    }

    // Show log viewer as a full-screen overlay
    if (showLogViewer) {
        LogViewerScreen(onBack = { showLogViewer = false })
        return
    }


    // Show the download catalog as a full-screen overlay
    if (showDownloadScreen) {
        DownloadScreen(
            viewModel = downloadViewModel,
            downloadsEnabled = !isFlashingNow,
            onBack = { showDownloadScreen = false },
            onRequestDestination = onRequestDownload
        )
        return
    }

    // Start scanning on entry, stop on exit
    DisposableEffect(Unit) {
        viewModel.startDeviceScan()
        onDispose { viewModel.stopDeviceScan() }
    }

    // Snackbar for file validation feedback
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.feedbackMessage.collect { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { 
                            Text(
                                stringResource(R.string.topbar_title),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ) 
                        },
                    actions = {
IconButton(onClick = { showLogViewer = true }) {
                            Icon(
                                imageVector = PhosphorIcons.Regular.Terminal,
                                contentDescription = stringResource(R.string.cd_view_logs),
                                tint = MaterialTheme.colorScheme.onBackground
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
            },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    Button(
                        onClick = { showConfirmDialog = true },
                        enabled = state is FlashState.Ready &&
                            selectedImage != null &&
                            selectedDevice != null &&
                            selectedDevice?.hasPermission == true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(stringResource(R.string.btn_flash), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        ) { padding ->
            val context = androidx.compose.ui.platform.LocalContext.current
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Image File Card
                val isoDescription = if (selectedImage?.isWindows == true) {
                    stringResource(R.string.card_image_windows_desc)
                } else {
                    null
                }

                StatusCard(
                    title = stringResource(R.string.card_image_title),
                    value = selectedImage?.name ?: stringResource(R.string.card_image_placeholder),
                    subtitle = selectedImage?.sizeFormatted,
                    icon = PhosphorIcons.Regular.Disc,
                    description = isoDescription,
                    isWarning = false,
                    onClick = { showSourceSheet = true }
                )

                val persistenceImage = selectedImage
                if (persistenceImage != null &&
                    persistenceImage.isLinux &&
                    persistenceImage.persistenceSupport != null
                ) {
                    PersistenceCard(
                        image = persistenceImage,
                        maxBytes = viewModel.persistenceMaxBytes(),
                        enabled = !isFlashingNow,
                        deviceSelected = selectedDevice != null,
                        onToggle = viewModel::setPersistenceEnabled,
                        onSizeSelected = viewModel::setPersistenceSize
                    )
                }

                val downloadCard = downloadRecords.firstOrNull { it.status.isActive }
                    ?: downloadRecords.firstOrNull { it.status == DownloadStatus.FAILED }
                if (downloadCard != null) {
                    DownloadStatusCard(
                        record = downloadCard,
                        onClick = {
                            downloadSheetId = downloadCard.id
                            downloadViewModel.focus(downloadCard.id)
                        },
                        onUseIso = if (downloadCard.isReady) {
                            { downloadViewModel.useIso(downloadCard.id) }
                        } else {
                            null
                        },
                        onDelete = if (downloadCard.isReady) {
                            { downloadViewModel.delete(downloadCard.id) }
                        } else {
                            null
                        }
                    )
                }

                // Target Drive Card
                StatusCard(
                    title = stringResource(R.string.card_drive_title),
                    value = selectedDevice?.name ?: stringResource(R.string.card_drive_placeholder),
                    subtitle = selectedDevice?.capacityFormatted,
                    icon = PhosphorIcons.Regular.Usb,
                    onClick = { 
                        if (availableDevices.size > 1) {
                            showDevicePicker = true
                        } else if (availableDevices.size == 1) {
                            val device = availableDevices[0]
                            if (!device.hasPermission) {
                                onRequestPermission(device)
                            } else {
                                viewModel.onDeviceSelected(device)
                            }
                        }
                    }
                )
                
                if (availableDevices.size > 1 && selectedDevice == null) {
                    Text(
                        text = stringResource(R.string.devices_detected, availableDevices.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                // Image-free, device-level extras: verify and backup the USB.
                val verifierDevice = selectedDevice
                val context = androidx.compose.ui.platform.LocalContext.current
                val opRunning = isVerifyRunning || isBackupRunning || isFlashingNow
                if (verifierDevice != null && verifierDevice.hasPermission) {
                    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument()
                    ) { uri ->
                        if (uri != null) {
                            com.hyntix.android.usbflasher.download.DownloadDestination.takePersistablePermission(
                                context,
                                uri
                            )
                            UsbBackupService.start(
                                context,
                                verifierDevice.device.deviceName,
                                uri.toString(),
                                verifierDevice.capacityBytes
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                        onClick = {
                            com.hyntix.android.usbflasher.domain.UsbOperationBus.setVerify(com.hyntix.android.usbflasher.domain.UsbVerificationState.Idle)
                            UsbVerificationService.start(
                                context,
                                verifierDevice.device.deviceName,
                                null,
                                verifierDevice.capacityBytes
                            )
                        },
                        enabled = !opRunning
                    ) {
                        Text("Verify USB")
                    }
                        OutlinedButton(
                        onClick = {
                            com.hyntix.android.usbflasher.domain.UsbOperationBus.setBackup(com.hyntix.android.usbflasher.domain.UsbBackupState.Idle)
                            backupLauncher.launch("usb-backup.img")
                        },
                        enabled = !opRunning
                    ) {
                        Text("Backup USB")
                    }
                    }
                }
                // Show operation results (non-blocking status)
                when (val vs = verificationState) {
                    is com.hyntix.android.usbflasher.domain.UsbVerificationState.Running -> {
                        Text("Verifying: ${DownloadFormatters.bytes(context, vs.bytesRead)} / ${DownloadFormatters.bytes(context, vs.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is com.hyntix.android.usbflasher.domain.UsbVerificationState.Finished -> {
                        val matched = vs.expectedSha256?.equals(vs.actualSha256, ignoreCase = true) ?: false
                        val verdict = if (vs.expectedSha256 == null) "⚠️ No expected hash provided"
                                     else if (matched) "✅ USB matches expected image"
                                     else "❌ Mismatch detected"
                        Text(verdict, style = MaterialTheme.typography.bodySmall,
                            color = if (matched || vs.expectedSha256 == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        Text("SHA-256: ${vs.actualSha256.take(12)}…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is com.hyntix.android.usbflasher.domain.UsbVerificationState.Failed -> {
                        Text(vs.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
                when (val bs = backupState) {
                    is com.hyntix.android.usbflasher.domain.UsbBackupState.Running -> {
                        Text("Backing up: ${DownloadFormatters.bytes(context, bs.bytesRead)} / ${DownloadFormatters.bytes(context, bs.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is com.hyntix.android.usbflasher.domain.UsbBackupState.Finished -> {
                        Text("Backup complete: ${DownloadFormatters.bytes(context, bs.writtenBytes)} written.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    is com.hyntix.android.usbflasher.domain.UsbBackupState.Failed -> {
                        Text(bs.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        }

        // Device Picker Dialog
        if (showDevicePicker) {
            AlertDialog(
                onDismissRequest = { showDevicePicker = false },
                title = { Text(stringResource(R.string.dialog_device_picker_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableDevices.forEach { device ->
                            Card(
                                onClick = {
                                    if (!device.hasPermission) {
                                        onRequestPermission(device)
                                    } else {
                                        viewModel.onDeviceSelected(device)
                                    }
                                    showDevicePicker = false
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedDevice?.device?.deviceName == device.device.deviceName)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(PhosphorIcons.Regular.Usb, contentDescription = null)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(device.capacityFormatted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDevicePicker = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        // Image source picker: existing file or official Linux download
        if (showSourceSheet) {
            AlertDialog(
                onDismissRequest = { showSourceSheet = false },
                title = { Text(stringResource(R.string.download_source_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.download_source_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card(
                            onClick = {
                                showSourceSheet = false
                                onSelectFile()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = PhosphorIcons.Regular.FileArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.download_choose_file),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = stringResource(R.string.download_choose_file_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Card(
                            onClick = {
                                showSourceSheet = false
                                showDownloadScreen = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = PhosphorIcons.Regular.DownloadSimple,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.download_download_iso),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = stringResource(R.string.download_download_iso_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSourceSheet = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        if (downloadUiError != null) {
            DownloadErrorDialog(
                error = downloadUiError!!,
                onDismiss = downloadViewModel::dismissError
            )
        }

        if (downloadMeteredPrompt != null) {
            MeteredDownloadDialog(
                prompt = downloadMeteredPrompt!!,
                onAllow = downloadViewModel::confirmMeteredDownload,
                onDismiss = downloadViewModel::dismissMeteredPrompt
            )
        }

        // Download progress sheet on the home screen
        val selectedDownload = downloadRecords.firstOrNull { it.id == downloadSheetId }
        if (selectedDownload != null) {
            DownloadProgressSheet(
                record = selectedDownload,
                onDismiss = {
                    downloadSheetId = null
                    downloadViewModel.dismissFocus()
                },
                onPause = { downloadViewModel.pause(selectedDownload.id) },
                onResume = { downloadViewModel.resume(selectedDownload.id) },
                onCancel = {
                    downloadViewModel.cancel(selectedDownload.id)
                    downloadSheetId = null
                },
                onDelete = {
                    downloadViewModel.delete(selectedDownload.id)
                    downloadSheetId = null
                },
                onUseIso = {
                    downloadViewModel.useIso(selectedDownload.id)
                    downloadSheetId = null
                }
            )
        }

        // Confirmation Dialog before flashing
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(R.string.dialog_confirm_flash_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.dialog_confirm_flash_body,
                                selectedDevice?.name ?: ""
                            )
                        )
                        val persistence = selectedImage?.persistence
                        if (persistence != null) {
                            Text(
                                stringResource(
                                    R.string.dialog_confirm_persistence_body,
                                    persistence.sizeFormatted,
                                    persistence.label
                                )
                            )
                            Text(
                                stringResource(R.string.persistence_not_encrypted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmDialog = false
                            viewModel.startFlash()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.btn_erase_and_flash))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        // Overlay for Flashing/Verifying/Success collected in its own composable
        // to prevent full MainScreen recomposition on 10Hz progress updates.
        FlashOverlay(viewModel = viewModel)
    }
    
    // Error Dialog (Simple Alert)
    if (state is FlashState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.cancel() },
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { Text((state as FlashState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancel() }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }
}

/// Separately-composed flash overlay so 10Hz progress updates don't trigger
/// full MainScreen recomposition. Only this composable recomposes.
@Composable
private fun FlashOverlay(viewModel: FlashViewModel) {
    val state by viewModel.state.collectAsState()
    if (state is FlashState.Flashing ||
        state is FlashState.Verifying ||
        state is FlashState.Persisting ||
        state is FlashState.Cancelling ||
        state is FlashState.Success
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = true,
                        onClick = { }
                    )
            )
            // Bottom sheet aligned to bottom
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                FlashingSheet(
                    state = state,
                    onCancel = {
                        if (state is FlashState.Success) viewModel.done()
                        else viewModel.cancel()
                    },
                    onRetryPersistence = viewModel::retryPersistence
                )
            }
        }
    }
}
