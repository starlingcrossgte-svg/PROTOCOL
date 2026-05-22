package com.protocol.app.openport2

import java.io.IOException

/**
 * Thrown by [TactrixBulkIo] when a bulk transfer returns a negative error
 * code that is not a normal per-chunk timeout (received == 0).
 *
 * On Android a negative return from bulkTransfer during normal operation
 * (not a zero-length timeout) reliably indicates that the USB device was
 * physically disconnected or the connection was closed while I/O was
 * in progress.
 */
class UsbDisconnectedException(message: String) : IOException(message)
