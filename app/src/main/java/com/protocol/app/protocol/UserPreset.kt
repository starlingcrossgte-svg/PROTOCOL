package com.protocol.app.protocol

import com.protocol.app.openport2.Ssm2Pid
import java.security.MessageDigest

/** A named group of parameters, saved and reusable. [pidIds] order decides the
 *  gauge layout on apply and the step order for a rotary control. */
data class UserPreset(
    val id: String,
    val name: String,
    val pidIds: List<String>
)

const val MAX_PRESET_PIDS = 15

/**
 * Keys a preset to the parameter set it was built against.
 *
 * Hashes content, not the file name or URI: names change on rename/re-download
 * and document URIs rotate on reinstall. Addresses are included so two defs that
 * reuse an id for a different address hash differently.
 */
fun parameterSetFingerprint(pids: List<Ssm2Pid>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (pid in pids.sortedBy { it.id }) {   // sorted: load order must not matter
        digest.update(pid.id.toByteArray())
        digest.update(':'.code.toByte())
        for (addr in pid.addresses) {
            digest.update(addr.high)
            digest.update(addr.mid)
            digest.update(addr.low)
        }
        digest.update('\n'.code.toByte())
    }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}
