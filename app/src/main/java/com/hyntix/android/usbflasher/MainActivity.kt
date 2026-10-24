package com.hyntix.android.usbflasher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hyntix.android.usbflasher.download.CreateIsoDocumentContract
import com.hyntix.android.usbflasher.download.DownloadDestination
import com.hyntix.android.usbflasher.download.DownloadGraph
import com.hyntix.android.usbflasher.ui.DownloadViewModel
import com.hyntix.android.usbflasher.ui.FlashViewModel
import com.hyntix.android.usbflasher.ui.MainScreen
import com.hyntix.android.usbflasher.ui.theme.USBFlasherTheme
import com.hyntix.android.usbflasher.util.ThemePreference

import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hyntix.android.usbflasher.data.FlashState

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hyntix.android.usbflasher.domain.FlashRepository
import com.hyntix.lib.androidusbflasher.AndroidUsbFlasher
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hyntix.android.usbflasher.USB_PERMISSION"
    }
    
    private val viewModel: FlashViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = FlashRepository(applicationContext, AndroidUsbFlasher(applicationContext))
                return FlashViewModel(repository) as T
            }
        }
    }

    private val downloadViewModel: DownloadViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val graph = DownloadGraph.init(applicationContext)
                return DownloadViewModel(
                    applicationContext,
                    graph.repository,
                    graph.store
                ) as T
            }
        }
    }

    private var notificationPermissionRequested = false
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        viewModel.startDeviceScan()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                device?.let { requestUsbPermission(it) }
                viewModel.startDeviceScan()
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                viewModel.startDeviceScan()
            }
        }
    }
    
    private fun requestUsbPermission(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            manager.requestPermission(device, pendingIntent)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Initialize in-app logger before anything else
        com.hyntix.android.usbflasher.util.AppLogger.init(applicationContext)
        DownloadGraph.init(applicationContext)
        
        // Register USB Receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        
        enableEdgeToEdge()
        
        setContent {
            val context = this@MainActivity
            val themeModeState = remember { mutableStateOf(ThemePreference.getThemeMode(context)) }
            val themeMode = themeModeState.value
            val isDark = when (themeMode) {
                ThemePreference.MODE_LIGHT -> false
                ThemePreference.MODE_DARK -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            USBFlasherTheme(darkThemeOverride = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsState()
                    val isFlashing = state is FlashState.Flashing ||
                        state is FlashState.Verifying ||
                        state is FlashState.Persisting ||
                        state is FlashState.Cancelling
                    
                    // Keep screen on during flashing — only react to phase transitions,
                    // not progress ticks. Derived boolean is stable between phase changes.
                    LaunchedEffect(isFlashing) {
                        if (isFlashing) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    // File picker launcher
                    val filePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        uri?.let { 
                            var name = getString(R.string.unknown_file)
                            var size = 0L
                            
                            contentResolver.query(it, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                                }
                            }
                            
                            viewModel.onFileSelected(it, name, size)
                        }
                    }

                    // SAF destination for an in-app ISO download
                    val createDocumentLauncher = rememberLauncherForActivityResult(
                        contract = CreateIsoDocumentContract()
                    ) { uri ->
                        val entry = downloadViewModel.takeDestinationRequest()
                        if (uri != null && entry != null) {
                            DownloadDestination.takePersistablePermission(applicationContext, uri)
                            downloadViewModel.startDownload(entry, uri)
                        }
                    }

                    // Notification permission is requested once, before the
                    // destination picker, so FGS progress is visible.
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) {
                        downloadViewModel.pendingDestinationRequest()?.let { entry ->
                            createDocumentLauncher.launch(entry.fileName)
                        }
                    }

                    val readyIso by downloadViewModel.readyIso.collectAsState()
                    LaunchedEffect(readyIso) {
                        readyIso?.let { iso ->
                            viewModel.onFileSelected(
                                iso.destinationUri.toUri(),
                                iso.displayName,
                                iso.sizeBytes
                            )
                            downloadViewModel.consumeReadyIso()
                        }
                    }

                    val showSettings = rememberSaveable { mutableStateOf(false) }
                    if (showSettings.value) {
                        com.hyntix.android.usbflasher.ui.SettingsDialog(
                            onDismiss = { showSettings.value = false },
                            onThemeChanged = { newMode ->
                                themeModeState.value = newMode
                            }
                        )
                    }
                    MainScreen(
                        viewModel = viewModel,
                        downloadViewModel = downloadViewModel,
                        onSelectFile = {
                            filePickerLauncher.launch(arrayOf(
                                "application/x-iso9660-image",
                                "application/x-raw-disk-image",
                                "application/octet-stream",
                            ))
                        },
                        onRequestDownload = { entry ->
                            downloadViewModel.rememberDestinationRequest(entry)
                            val needsPermission =
                                ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED &&
                                    !notificationPermissionRequested
                            if (needsPermission) {
                                notificationPermissionRequested = true
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                createDocumentLauncher.launch(entry.fileName)
                            }
                        },
                        onRequestPermission = { deviceInfo ->
                            requestUsbPermission(deviceInfo.device)
                        }
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}