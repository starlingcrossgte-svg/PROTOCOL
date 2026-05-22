package com.protocol.app.openport2

data class TactrixCommandLog(
    val stepIndex: Int,
    val stepLabel: String,
    val requestAscii: String,
    val requestHex: String,
    val responseAscii: String,
    val responseHex: String,
    val durationMs: Long,
    val outcome: Outcome,
    val notes: String = ""
) {
    enum class Outcome {
        PASS,
        FAIL_NO_RESPONSE,
        FAIL_ADAPTER_ERROR,
        FAIL_TIMEOUT,
        FAIL_INTERNAL
    }
}
