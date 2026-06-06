package com.protocol.app.obdlink

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * ELM327/STN text-protocol exchange over an already-open stream pair (OBDLink
 * MX+ over Bluetooth, or the TCP twin to the emulator).
 *
 * A single background reader thread owns the input stream: it reads
 * continuously, mirrors EVERYTHING to [ObdLinkTrafficLog] as IN events (split
 * one log line per reply/frame), and appends to a shared buffer. [sendAscii]
 * writes a command and then waits on that buffer for the '>' prompt instead of
 * reading the stream itself — so per-command I/O and the continuous logger
 * never fight over the one InputStream.
 *
 * Why the reader thread: the old loop read exactly one reply per command and
 * then nothing touched the socket, so asynchronous adapter output — STN
 * periodic-message streams (STPPMA), unsolicited frames — was never read and
 * never logged, and it corrupted the next command's reply. Now every byte the
 * adapter sends shows up in the log, whether or not a command is waiting on it.
 *
 * Pure I/O: knows nothing about SSM2 or CAN. [ObdLinkBtManager] layers the ELM
 * channel setup + SSM2 payloads on top.
 */
class ObdLinkBtTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: (direction: String, text: String) -> Unit = { _, _ -> }
) {
    private val bufLock = Any()
    private val inBuf = StringBuilder()      // guarded by bufLock; the sendAscii reply window
    @Volatile private var running = true
    // True between [beginMonitor] and [stopMonitor]. While monitoring (e.g. STMA)
    // the adapter streams frames and emits NO '>' prompt — so NOTHING may wait for
    // '>' until the 0x0D stop is sent. [sendAscii] enforces this by stopping the
    // monitor first; the stream is read with [takeBuffered] instead.
    @Volatile private var monitoring = false

    @Suppress("unused")
    private val reader = Thread({ readerLoop() }, "obdlink-reader").apply {
        isDaemon = true
        start()
    }

    /**
     * The single owner of [input]. Reads forever, mirrors every byte to the
     * traffic log (one line per '\r'- or '>'-terminated unit), and appends to
     * [inBuf] for [sendAscii] to consume. Exits when the stream closes (socket
     * teardown) or [close] is called.
     */
    private fun readerLoop() {
        val buf = ByteArray(512)
        val line = StringBuilder()
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                synchronized(bufLock) {
                    for (i in 0 until n) inBuf.append((buf[i].toInt() and 0xFF).toChar())
                    // Bound the reply window so an unread periodic stream can't
                    // grow it without limit (the log has its own ring cap).
                    if (inBuf.length > 8192) inBuf.delete(0, inBuf.length - 4096)
                }
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '\r' || c == '>') {
                        val unit = line.toString().trim()
                        if (unit.isNotEmpty()) log("IN", unit)
                        line.setLength(0)
                    } else {
                        line.append(c)
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed underneath us (disconnect) — stop quietly
        } finally {
            running = false
        }
    }

    /**
     * Sends [command] (a trailing '\r' is added if absent) and returns the
     * adapter's ASCII response up to and including the '>' prompt, or whatever
     * arrived before [timeoutMs]. The reply text is logged by the reader
     * thread; here we only log the OUT command. Propagates IOException from the
     * write (caller treats it as a dropped link).
     */
    fun sendAscii(command: String, timeoutMs: Long = 1000L): String {
        // Can't wait for '>' while a monitor is streaming (it never sends one).
        // Stop it first with the 0x0D, which also re-enables the prompt.
        if (monitoring) stopMonitor()
        val line = if (command.endsWith("\r")) command else "$command\r"
        log("OUT", line.trimEnd('\r'))
        synchronized(bufLock) { inBuf.setLength(0) }   // fresh window for this reply
        output.write(line.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = synchronized(bufLock) { inBuf.toString() }
            if (snapshot.indexOf('>') >= 0) return snapshot
            try { Thread.sleep(3) } catch (_: InterruptedException) { break }
        }
        return synchronized(bufLock) { inBuf.toString() }
    }

    /** Discards anything currently buffered for the next [sendAscii] reply window. */
    fun drain(maxMs: Long = 200L) {
        synchronized(bufLock) { inBuf.setLength(0) }
    }

    /**
     * Write [command] (trailing '\r' added if absent) and return immediately —
     * for continuous streaming, where the caller polls [takeBuffered] and emits
     * frames as they arrive instead of waiting out the '>' prompt.
     */
    fun sendNoWait(command: String) {
        val line = if (command.endsWith("\r")) command else "$command\r"
        log("OUT", line.trimEnd('\r'))
        output.write(line.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    /**
     * Start a monitor stream (e.g. `STMA`): write [command] and return at once,
     * marking the channel as monitoring. While monitoring the adapter emits NO
     * '>' prompt — read the stream with [takeBuffered] and DO NOT call anything
     * that waits for '>' until [stopMonitor]. The monitoring flag is set only
     * after the write succeeds, so a failed write doesn't leave a false state.
     */
    fun beginMonitor(command: String) {
        sendNoWait(command)
        monitoring = true
    }

    /**
     * Stop a monitor started with [beginMonitor] by sending the bare **0x0D**
     * (CR) — the documented STMA stop — then wait briefly for the trailing
     * STOPPED/'>' and clear it so it can't leak into the next reply window. Safe
     * to call when not monitoring (no-op) and when the link is already dead
     * (write failure is swallowed).
     */
    fun stopMonitor(timeoutMs: Long = 300L) {
        if (!monitoring) return
        monitoring = false
        try {
            output.write(0x0D)               // 0x0D alone stops the monitor
            output.flush()
            log("OUT", "0x0D (stop monitor)")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val snap = synchronized(bufLock) { inBuf.toString() }
                if (snap.indexOf('>') >= 0) break
                Thread.sleep(3)
            }
        } catch (_: Exception) {
            // link already torn down — nothing to stop
        } finally {
            synchronized(bufLock) { inBuf.setLength(0) }
        }
    }

    /** Return and clear whatever the reader thread has buffered since the last
     *  call (the raw chars, incl. any '\r'/'>'). For incremental stream reads. */
    fun takeBuffered(): String = synchronized(bufLock) {
        val s = inBuf.toString()
        inBuf.setLength(0)
        s
    }

    /** Stop the reader thread. The socket close that follows also unblocks it. */
    fun close() {
        running = false
    }
}
