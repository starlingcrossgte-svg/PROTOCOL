package com.protocol.app.flash.engine

import java.io.IOException

/**
 * Thrown by [FlashBulkIo] when a USB bulk transfer returns a negative error
 * code that is not a normal per-chunk timeout — on Android this reliably means
 * the device was physically disconnected or the connection was closed mid-I/O.
 *
 * The flash silo keeps its own copy (zero shared code with the logging path),
 * mirroring `openport2.UsbDisconnectedException`.
 */
class FlashUsbException(message: String) : IOException(message)
