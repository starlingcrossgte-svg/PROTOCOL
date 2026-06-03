package com.protocol.app.openport2

/**
 * Thrown by a live source when the adapter is connected and the channel is
 * open, but the ECU isn't answering — a sustained run of no-reply responses
 * (`are` on CAN, no `ar3` / NO DATA / BUS ERROR on K-line, etc.). The
 * ViewModel catches this to stop polling and report "no ECU" instead of
 * hammering the bus forever.
 *
 * Shared across every live-source transport: OpenPort CAN/K-line and OBDLink
 * CAN/K-line.
 */
class NoEcuResponseException(message: String) : Exception(message)
