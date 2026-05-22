package com.protocol.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbPermissionHelper(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TACTRIX_VENDOR_ID = 1027
        private const val TACTRIX_PRODUCT_ID = 52301
    }

    fun requestUsbPermission(device: UsbDevice, actionUsbPermission: String) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        EcuLogger.usb("requestUsbPermission called")
        EcuLogger.usb("requestUsbPermission flags: $flags")
        EcuLogger.usb(
            "requestUsbPermission device vendor=${device.vendorId} product=${device.productId}"
        )

        val permissionIntent = Intent(actionUsbPermission).apply {
            setPackage(context.packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            permissionIntent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
        EcuLogger.usb("usbManager.requestPermission invoked")
    }

    fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    fun isTactrixDevice(device: UsbDevice): Boolean {
        return device.vendorId == TACTRIX_VENDOR_ID &&
            device.productId == TACTRIX_PRODUCT_ID
    }
}
