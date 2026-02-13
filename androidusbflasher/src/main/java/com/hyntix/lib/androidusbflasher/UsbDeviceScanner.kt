package com.hyntix.lib.androidusbflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Helper class to discover USB Mass Storage devices and manage permissions.
 */
class UsbDeviceScanner(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Get a list of currently connected USB devices that appear to be mass storage.
     */
    fun getMassStorageDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { isMassStorage(it) }
    }

    /**
     * Check if a device is a mass storage device.
     */
    fun isMassStorage(device: UsbDevice): Boolean {
        // Check at device level
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        
        // Check all interfaces
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        }
        return false
    }

    /**
     * Request permission for a USB device.
     * @param device The device to request permission for.
     * @param action Intent action for the permission result.
     */
    fun requestPermission(device: UsbDevice, action: String) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val intent = Intent(action).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Check if we have permission for a device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }
}
