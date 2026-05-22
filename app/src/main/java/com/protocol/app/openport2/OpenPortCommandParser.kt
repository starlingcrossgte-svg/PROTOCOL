package com.protocol.app.openport2

/**
 * Parsed structure of a single Tactrix line-protocol command, derived from
 * the ASCII actually written on the USB bulk-OUT channel.
 *
 * Recognized verbs and the position of the trailing request-id token:
 *
 *   ati                                 — no reqid
 *   ata <reqid>
 *   atp <a> <b> <reqid>                 (binary tail follows on the wire)
 *   ato<ch> <flags> <baud> <timeout> <reqid>
 *   ats<ch> <a> <b> <reqid>
 *   atf<ch> <a> <b> <c> <reqid>         (binary tail follows on the wire)
 *   atv <a> <b> <reqid>
 *   att<ch> <payloadLen> <unk> <timeoutMicros> <reqid>   (binary tail follows on the wire)
 *
 * For unrecognized verbs, a partially-filled command is returned rather than
 * null so the run log can still print whatever was on the wire.
 */
data class OpenPortCommand(
    val verb: String,
    val channel: Int?,
    val payloadLen: Int?,
    val timeoutMicros: Long?,
    val reqId: Int?,
    val rawAscii: String
)

object OpenPortCommandParser {

    fun parseOpenPortCommand(requestAscii: String): OpenPortCommand? {
        if (requestAscii.isBlank()) return null
        val trimmed = requestAscii.trimEnd('\r', '\n', ' ').substringBefore(" + ")
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val verb = tokens[0]
        val channel = trailingDigit(verb)

        return when {
            verb == "ati" -> OpenPortCommand(
                verb = "ati",
                channel = null,
                payloadLen = null,
                timeoutMicros = null,
                reqId = null,
                rawAscii = trimmed
            )
            verb == "ata" -> OpenPortCommand(
                verb = "ata",
                channel = null,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb == "atp" -> OpenPortCommand(
                verb = "atp",
                channel = null,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb == "atv" -> OpenPortCommand(
                verb = "atv",
                channel = null,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb.startsWith("ato") -> OpenPortCommand(
                verb = verb,
                channel = channel,
                payloadLen = null,
                timeoutMicros = tokens.getOrNull(3)?.toLongOrNull(),
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb.startsWith("ats") -> OpenPortCommand(
                verb = verb,
                channel = channel,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb.startsWith("atf") -> OpenPortCommand(
                verb = verb,
                channel = channel,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            verb.startsWith("att") -> OpenPortCommand(
                verb = verb,
                channel = channel,
                payloadLen = tokens.getOrNull(1)?.toIntOrNull(),
                timeoutMicros = tokens.getOrNull(3)?.toLongOrNull(),
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
            else -> OpenPortCommand(
                verb = verb,
                channel = channel,
                payloadLen = null,
                timeoutMicros = null,
                reqId = tokens.lastOrNull()?.toIntOrNull(),
                rawAscii = trimmed
            )
        }
    }

    private fun trailingDigit(verb: String): Int? {
        if (verb.length < 4) return null
        val last = verb.last()
        return if (last.isDigit()) last.digitToInt() else null
    }
}
