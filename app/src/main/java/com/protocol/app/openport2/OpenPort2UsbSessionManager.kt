package com.protocol.app.openport2

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

sealed class OpenPort2SessionResult {
    data class Connected(val session: OpenPort2UsbSession) : OpenPort2SessionResult()
    data class Failed(val reason: String) : OpenPort2SessionResult()
}

class OpenPort2UsbSessionManager(
    private val context: Context,
    private val vendorId: Int,
    private val productId: Int
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun openSession(device: UsbDevice): OpenPort2SessionResult {
        val connection = usbManager.openDevice(device)
            ?: return OpenPort2SessionResult.Failed("openDevice returned null")

        val usbInterface = findBulkInterface(device)
        if (usbInterface == null) {
            closeSafely(connection, null)
            return OpenPort2SessionResult.Failed("No interface with bulk IN and bulk OUT endpoints")
        }

        if (!connection.claimInterface(usbInterface, true)) {
            closeSafely(connection, null)
            return OpenPort2SessionResult.Failed("claimInterface returned false")
        }

        val endpointOut = findBulkEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
        val endpointIn = findBulkEndpoint(usbInterface, UsbConstants.USB_DIR_IN)

        if (endpointOut == null || endpointIn == null) {
            closeSafely(connection, usbInterface)
            return OpenPort2SessionResult.Failed("Missing bulk endpoint(s)")
        }

        return OpenPort2SessionResult.Connected(
            OpenPort2UsbSession(
                device = device,
                connection = connection,
                usbInterface = usbInterface,
                endpointOut = endpointOut,
                endpointIn = endpointIn
            )
        )
    }

    fun closeSession(session: OpenPort2UsbSession) {
        closeSafely(session.connection, session.usbInterface)
    }

    private fun closeSafely(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface?
    ) {
        try {
            if (usbInterface != null) {
                connection.releaseInterface(usbInterface)
            }
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
            if (hasIn && hasOut) {
                return iface
            }
        }
        return null
    }

    private fun findBulkEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) {
                return ep
            }
        }
        return null
    }
}
