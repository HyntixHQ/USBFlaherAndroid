package com.hyntix.android.usbflasher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    // Checking for Windows ISO
    val winIsoWarning = selectedImage?.let {
        if (it.name.contains("win", ignoreCase = true) || it.name.contains("windows", ignoreCase = true)) 
            "Windows images are not supported." 
        else null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            "USB Flasher", 
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ) 
                    },
                    actions = {
                        IconButton(onClick = { showLogViewer = true }) {
                            Icon(
                                imageVector = PhosphorIcons.Regular.Terminal,
                                contentDescription = "View logs",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    Button(
                        onClick = { viewModel.startFlash() },
                        enabled = selectedImage != null && selectedDevice != null && (selectedDevice?.hasPermission == true) && winIsoWarning == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Flash", style = MaterialTheme.typography.titleMedium)
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
                StatusCard(
                    title = "Image File",
                    value = selectedImage?.name ?: "Tap to Select",
                    subtitle = selectedImage?.sizeFormatted,
                    icon = PhosphorIcons.Regular.Disc,
                    description = winIsoWarning,
                    isWarning = winIsoWarning != null,
                    onClick = onSelectFile
                )

                // Target Drive Card
                StatusCard(
                    title = "Target Drive",
                    value = selectedDevice?.name ?: "Connect a USB Drive",
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
                        text = "${availableDevices.size} drives detected. Tap above to choose.",
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
                title = { Text("Select USB Drive") },
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
                        Text("Cancel")
                    }
                }
            )
        }

        // Overlay for Flashing/Verifying/Success
        if (state is FlashState.Flashing || state is FlashState.Verifying || state is FlashState.Success) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = true) { /* Consume clicks */ }
            )
            
            // Bottom Sheet
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                FlashingSheet(
                    state = state,
                    onCancel = { viewModel.cancel() }
                )
            }
        }
    }
    
    // Error Dialog (Simple Alert)
    if (state is FlashState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.cancel() }, // Reset to Idle
            title = { Text("Error") },
            text = { Text((state as FlashState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancel() }) {
                    Text("OK")
                }
            }
        )
    }
}
