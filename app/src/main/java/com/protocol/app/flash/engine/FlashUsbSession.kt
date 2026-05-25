package com.protocol.app.flash.engine

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * One open USB connection to the OpenPort 2.0 for the flash silo.
 *
 * Dedicated copy of the logging path's `OpenPort2UsbSession` — the flash
 * feature shares no code with logging.
 */
data class FlashUsbSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val endpointOut: UsbEndpoint,
    val endpointIn: UsbEndpoint
)
