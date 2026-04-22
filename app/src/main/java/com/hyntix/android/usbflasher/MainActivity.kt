package com.hyntix.android.usbflasher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hyntix.android.usbflasher.ui.FlashViewModel
import com.hyntix.android.usbflasher.ui.MainScreen
import com.hyntix.android.usbflasher.ui.theme.USBFlasherTheme

import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
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
import android.os.Build

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
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
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
        super.onCreate(savedInstanceState)
        
        // Initialize in-app logger before anything else
        com.hyntix.android.usbflasher.util.AppLogger.init(applicationContext)
        
        // Register USB Receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        
        enableEdgeToEdge()
        
        setContent {
            USBFlasherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsState()
                    
                    // Keep screen on during flashing
                    LaunchedEffect(state) {
                        if (state is FlashState.Flashing || state is FlashState.Verifying) {
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
                            var name = "Unknown.iso"
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
                    
                    MainScreen(
                        viewModel = viewModel,
                        onSelectFile = {
                            filePickerLauncher.launch(arrayOf(
                                "application/x-iso9660-image",
                                "application/x-raw-disk-image",
                                "application/octet-stream",
                                "*/*"
                            ))
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