package com.hyntix.android.usbflasher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.FlashState
import com.hyntix.android.usbflasher.data.UsbDeviceInfo
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.Usb
import com.adamglin.phosphoricons.regular.Terminal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: FlashViewModel,
    onSelectFile: () -> Unit,
    onRequestPermission: (UsbDeviceInfo) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val selectedImage by viewModel.selectedImageInfo.collectAsState()
    val selectedDevice by viewModel.selectedDeviceInfo.collectAsState()
    
    var showDevicePicker by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Show log viewer as a full-screen overlay
    if (showLogViewer) {
        LogViewerScreen(onBack = { showLogViewer = false })
        return
    }

    // Start scanning on entry, stop on exit
    DisposableEffect(Unit) {
        viewModel.startDeviceScan()
        onDispose { viewModel.stopDeviceScan() }
    }

    // Windows ISO is now supported via WIM Splitting!
    val winIsoWarning = null

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
                        enabled = selectedImage != null && selectedDevice != null && (selectedDevice?.hasPermission == true),
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
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
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
                    onClick = onSelectFile
                )

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

        // Confirmation Dialog before flashing
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(R.string.dialog_confirm_flash_title)) },
                text = { 
                    Text(stringResource(R.string.dialog_confirm_flash_body, selectedDevice?.name ?: "")) 
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
    if (state is FlashState.Flashing || state is FlashState.Verifying || state is FlashState.Success) {
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
                    }
                )
            }
        }
    }
}
