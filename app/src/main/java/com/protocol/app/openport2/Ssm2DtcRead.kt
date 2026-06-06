package com.protocol.app.openport2

import com.protocol.app.obdlink.ObdLinkBtTransport
import com.protocol.app.obdlink.ObdLinkSsm2Can

/**
 * One-shot, read-only SSM2 diagnostic-trouble-code read.
 *
 * Reuses the same A8 read-address machinery the live-data path uses
 * ([Ssm2AddressQuery] + each transport's existing send/parse), pointed at the
 * ECU's DTC status bytes ([DtcCatalog]). No transport changes — this is a
 * sibling consumer of the channel the user already connected.
 *
 * The status block is read in small chunks (the same size class as a live
 * poll) so a single K-line frame never exceeds what the adapter handles
 * reliably. A chunk that doesn't answer simply leaves its addresses absent,
 * which decodes as "no code set"; the ok/total chunk counts surface whether
 * the read actually reached the ECU versus silently read nothing.
 *
 * ECM only (destination 0x10). The TCM keeps its own DTC space — a later add.
 */
object Ssm2DtcRead {

    // Addresses per A8 request on K-line. A live poll uses up to ~16; 20 keeps
    // each DTC request the same size class (frame ~= 5 + 2 + 20*3 = 67 bytes).
    private const val KLINE_CHUNK = 20

    // Stop a block early once this many consecutive chunks miss with nothing
    // read yet — bounds the "ECU not on this bus/mode" case instead of grinding
    // every address at the per-request timeout.
    private const val GIVE_UP_AFTER = 8

    data class Result(
        val current: List<DtcDef>,
        val stored: List<DtcDef>,
        val currentOkChunks: Int,
        val currentChunks: Int,
        val storedOkChunks: Int,
        val storedChunks: Int
    ) {
        /** True when at least one status chunk was actually read from the ECU. */
        val reachedEcu: Boolean get() = currentOkChunks > 0 || storedOkChunks > 0
    }

    fun readObdLinkKline(transport: ObdLinkBtTransport): Result {
        val read: (List<Ssm2Address>) -> IntArray? = { addrs ->
            val frame = Ssm2AddressQuery.buildA8Query(addrs, Ssm2AddressQuery.DEST_ECM)
            val cmd = "STPX d:${ObdLinkSsm2Can.toElmHex(frame)},r:1,t:1000"
            val ascii = try { transport.sendAscii(cmd, timeoutMs = 2000L) } catch (e: Exception) { null }
            if (ascii == null) null else extractKlineA8(ObdLinkSsm2Can.parseElmHex(ascii), addrs.size)
        }
        return readBoth(KLINE_CHUNK, read)
    }

    fun readObdLinkCan(transport: ObdLinkBtTransport): Result {
        val read: (List<Ssm2Address>) -> IntArray? = { addrs ->
            val reqHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildReadPayload(addrs))
            val ascii = try { transport.sendAscii(reqHex, timeoutMs = 600L) } catch (e: Exception) { null }
            if (ascii == null) null else ObdLinkSsm2Can.parseReadResponse(ObdLinkSsm2Can.parseElmHex(ascii), addrs.size)
        }
        // One address per ISO-TP single frame, like the OBDLink CAN live source.
        return readBoth(1, read)
    }

    fun readOpenPortKline(client: TactrixClient): Result {
        val read: (List<Ssm2Address>) -> IntArray? = { addrs ->
            val q = Ssm2AddressQuery.buildA8Query(addrs, Ssm2AddressQuery.DEST_ECM)
            val outcome = client.sendAsciiPlusBinary(
                asciiBodyWithoutReqId = "att$K_LINE_CHANNEL ${q.size} 0 400000",
                binaryTail = q,
                appendReqId = true,
                expectVehicleFrameOnChannel = K_LINE_CHANNEL,
                expectedReplySource = Ssm2AddressQuery.DEST_ECM,
                readTimeoutMs = 1500L
            )
            if (!outcome.matched) {
                null
            } else {
                val raw = TactrixHex.parseHexPayload(outcome.responseHex.replace(" ", ""))
                val frame = client.extractVehicleFrame(raw, K_LINE_CHANNEL, Ssm2AddressQuery.DEST_ECM)
                val parsed = frame?.let { Ssm2FrameParser.parseSsm2Frame(it) }
                if (parsed == null || parsed.truncated || !parsed.checksumValid) null
                else Ssm2AddressQuery.parseA8Response(parsed, addrs.size)
            }
        }
        return readBoth(KLINE_CHUNK, read)
    }

    fun readOpenPortCan(source: OpenPortCanLiveSource): Result {
        val opened = source.initChannel()
        // One address per ISO-TP single frame, like the OpenPort CAN live source.
        val read: (List<Ssm2Address>) -> IntArray? = { addrs ->
            if (opened) source.readAddressesOnce(addrs) else null
        }
        return readBoth(1, read)
    }

    private fun readBoth(chunkSize: Int, read: (List<Ssm2Address>) -> IntArray?): Result {
        val cur = readBlock(DtcCatalog.currentAddrInts, chunkSize, read)
        val sto = readBlock(DtcCatalog.storedAddrInts, chunkSize, read)
        return Result(
            current = DtcCatalog.decodeCurrent(cur.first),
            stored = DtcCatalog.decodeStored(sto.first),
            currentOkChunks = cur.second,
            currentChunks = chunkCount(DtcCatalog.currentAddrInts.size, chunkSize),
            storedOkChunks = sto.second,
            storedChunks = chunkCount(DtcCatalog.storedAddrInts.size, chunkSize)
        )
    }

    private fun chunkCount(n: Int, size: Int) = (n + size - 1) / size

    /** Read [addrInts] in [chunkSize] groups → (addr int -> byte) map + ok-chunk count. */
    private fun readBlock(
        addrInts: List<Int>,
        chunkSize: Int,
        read: (List<Ssm2Address>) -> IntArray?
    ): Pair<Map<Int, Int>, Int> {
        val out = HashMap<Int, Int>()
        var ok = 0
        var consecutiveMiss = 0
        var i = 0
        while (i < addrInts.size) {
            val slice = addrInts.subList(i, minOf(i + chunkSize, addrInts.size))
            val bytes = read(slice.map { DtcCatalog.toAddress(it) })
            if (bytes != null && bytes.size == slice.size) {
                ok++
                consecutiveMiss = 0
                for ((j, a) in slice.withIndex()) out[a] = bytes[j]
            } else {
                consecutiveMiss++
                if (ok == 0 && consecutiveMiss >= GIVE_UP_AFTER) break
            }
            i += chunkSize
        }
        return out to ok
    }

    /** Scan an OBDLink K-line reply buffer for the LAST valid 80 F0 10 … E8 frame. */
    private fun extractKlineA8(bytes: ByteArray, addressCount: Int): IntArray? {
        var result: IntArray? = null
        var i = 0
        while (i <= bytes.size - 5) {
            if ((bytes[i].toInt() and 0xFF) == 0x80 &&
                (bytes[i + 1].toInt() and 0xFF) == 0xF0 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x10
            ) {
                val len = bytes[i + 3].toInt() and 0xFF
                val total = len + 5
                if (i + total <= bytes.size) {
                    val parsed = Ssm2FrameParser.parseSsm2Frame(bytes.copyOfRange(i, i + total))
                    if (parsed != null && !parsed.truncated && parsed.checksumValid) {
                        val raw = Ssm2AddressQuery.parseA8Response(parsed, addressCount)
                        if (raw != null) {
                            result = raw
                            i += total
                            continue
                        }
                    }
                }
            }
            i++
        }
        return result
    }
}
