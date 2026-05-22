package com.protocol.app.openport2

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

data class OpenPort2UsbSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val endpointOut: UsbEndpoint,
    val endpointIn: UsbEndpoint
)
