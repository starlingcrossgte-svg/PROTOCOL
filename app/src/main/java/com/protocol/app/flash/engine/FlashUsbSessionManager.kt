package com.protocol.app.flash.engine

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

sealed class FlashUsbResult {
    data class Connected(val session: FlashUsbSession) : FlashUsbResult()
    data class Failed(val reason: String) : FlashUsbResult()
}

/**
 * Acquires and opens the OpenPort 2.0 USB device for the flash silo.
 *
 * Dedicated copy of the logging path's `OpenPort2UsbSessionManager` (zero
 * shared code). The OS-level USB permission is an app/device property, so this
 * manager relies on permission already granted through the app's USB intent
 * filter; it does not duplicate the permission-request UI.
 *
 * Exclusive ownership of the single physical device is enforced higher up (the
 * flash silo takes the device only while no logging session holds it); this
 * class just finds, opens, and closes.
 */
class FlashUsbSessionManager(private val context: Context) {

    companion object {
        // Tactrix OpenPort 2.0 — must match the logging path's IDs.
        const val TACTRIX_VENDOR_ID = 1027
        const val TACTRIX_PRODUCT_ID = 52301
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == TACTRIX_VENDOR_ID && it.productId == TACTRIX_PRODUCT_ID
        }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun openSession(device: UsbDevice): FlashUsbResult {
        val connection = usbManager.openDevice(device)
            ?: return FlashUsbResult.Failed("openDevice returned null")

        val usbInterface = findBulkInterface(device)
        if (usbInterface == null) {
            closeSafely(connection, null)
            return FlashUsbResult.Failed("No interface with bulk IN and bulk OUT endpoints")
        }
        if (!connection.claimInterface(usbInterface, true)) {
            closeSafely(connection, null)
            return FlashUsbResult.Failed("claimInterface returned false")
        }
        val endpointOut = findBulkEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
        val endpointIn = findBulkEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
        if (endpointOut == null || endpointIn == null) {
            closeSafely(connection, usbInterface)
            return FlashUsbResult.Failed("Missing bulk endpoint(s)")
        }
        return FlashUsbResult.Connected(
            FlashUsbSession(device, connection, usbInterface, endpointOut, endpointIn)
        )
    }

    fun closeSession(session: FlashUsbSession) = closeSafely(session.connection, session.usbInterface)

    private fun closeSafely(connection: UsbDeviceConnection, usbInterface: UsbInterface?) {
        try {
            if (usbInterface != null) connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    private fun findBulkInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val hasIn = findBulkEndpoint(iface, UsbConstants.USB_DIR_IN) != null
            val hasOut = findBulkEndpoint(iface, UsbConstants.USB_DIR_OUT) != null
            if (hasIn && hasOut) return iface
        }
        return null
    }

    private fun findBulkEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) return ep
        }
        return null
    }
}
