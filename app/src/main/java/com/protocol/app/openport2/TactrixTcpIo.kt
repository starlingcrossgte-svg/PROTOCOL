package com.protocol.app.openport2

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/*
 * TCP-socket implementation of TactrixIo.
 *
 * Connects to a host process that speaks byte-identical Tactrix adapter
 * traffic on a TCP port. Wire semantics match TactrixBulkIo so that the
 * Tactrix init handshake, SSM2 framing, and ar-frame parsing inside
 * TactrixClient see no difference.
 *
 * Constructor connects synchronously. Caller MUST construct from a
 * background thread (otherwise NetworkOnMainThreadException).
 *
 * Throws UsbDisconnectedException on socket errors so the existing
 * upstream "transport gone away" handling fires unchanged.
 */
class TactrixTcpIo(
    host: String,
    port: Int,
    connectTimeoutMs: Int = 2000
) : TactrixIo {

    companion object {
        private const val READ_CHUNK_SIZE = 512
        private const val READ_POLL_MS = 50
    }

    private val socket: Socket = Socket().also { s ->
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw UsbDisconnectedException("tcp connect to $host:$port failed: ${e.message}")
        }
    }
    private val output = socket.getOutputStream()
    private val input = socket.getInputStream()

    override fun write(packet: ByteArray): Int {
        try {
            output.write(packet)
            output.flush()
        } catch (e: IOException) {
            throw UsbDisconnectedException("tcp write failed: ${e.message}")
        }
        UsbTrafficLog.recordWrite(packet)
        return packet.size
    }

    override fun drain(maxTotalMs: Long) {
        val deadline = System.currentTimeMillis() + maxTotalMs
        val buf = ByteArray(READ_CHUNK_SIZE)
        while (System.currentTimeMillis() < deadline) {
            try {
                socket.soTimeout = 40
                val n = input.read(buf)
                if (n <= 0) return
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: IOException) {
                return
            }
        }
    }

    override fun readUntil(totalTimeoutMs: Long, predicate: (ByteArray) -> Boolean): ReadResult {
        val accumulated = ArrayList<Byte>(512)
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        val buffer = ByteArray(READ_CHUNK_SIZE)

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                return ReadResult(accumulated.toByteArray(), matched = false)
            }

            val pollMs = remaining.coerceAtMost(READ_POLL_MS.toLong()).toInt().coerceAtLeast(1)
            socket.soTimeout = pollMs

            val received = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                0
            } catch (e: IOException) {
                throw UsbDisconnectedException("tcp read failed: ${e.message}")
            }

            if (received < 0) {
                throw UsbDisconnectedException("tcp read returned EOF (peer closed)")
            }

            if (received > 0) {
                UsbTrafficLog.recordRead(buffer.copyOfRange(0, received))
                for (i in 0 until received) accumulated.add(buffer[i])
                val snapshot = accumulated.toByteArray()
                if (predicate(snapshot)) return ReadResult(snapshot, matched = true)
            }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}
