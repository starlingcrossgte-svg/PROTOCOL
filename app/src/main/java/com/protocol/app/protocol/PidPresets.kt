package com.protocol.app.protocol

import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import com.protocol.app.openport2.Ssm2Pids

/**
 * A named group of parameters that can be loaded onto the Live Data page in
 * one tap. Presets are fixed "pages" over the full PID list — selecting one
 * replaces the current gauges with that group's, so the user can page through
 * every parameter ten-at-a-time for quick PID verification.
 */
data class PidPreset(val label: String, val pidIds: List<String>)

/**
 * The 12 presets, derived from [Ssm2Pids.DEFAULT_DEMO_PIDS] in declaration
 * order. ECU and TCM parameters are kept in SEPARATE presets (never mixed) —
 * an ECU preset holds only ECU PIDs, a TCM preset only TCM PIDs.
 *
 * Each preset targets [TARGET] PIDs. When a category's final chunk would be
 * tiny (< [MIN_TAIL]) it's folded into the previous preset so no preset is
 * orphaned with one or two gauges. With the current list that yields:
 *   - ECU (71) -> 7 presets  (six of 10, one of 11)
 *   - TCM (46) -> 5 presets  (four of 10, one of 6)
 * = 12 presets total.
 */
object PidPresets {
    private const val TARGET = 10
    private const val MIN_TAIL = 5

    val PRESETS: List<PidPreset> = build()

    private fun build(): List<PidPreset> {
        // Only trusted PIDs feed the quick presets — unverified candidates are
        // kept out so the presets stay clean.
        val ecu = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.category == Ssm2PidCategory.ECU && it.verified }
        val tcm = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.category == Ssm2PidCategory.TCM && it.verified }
        val out = ArrayList<PidPreset>()
        chunk(ecu).forEachIndexed { i, group ->
            out.add(PidPreset("ECU ${i + 1}", group.map { it.id }))
        }
        chunk(tcm).forEachIndexed { i, group ->
            out.add(PidPreset("TCM ${i + 1}", group.map { it.id }))
        }
        return out
    }

    /** Split [pids] into groups of [TARGET], folding a too-small tail group
     *  into the one before it. */
    private fun chunk(pids: List<Ssm2Pid>): List<List<Ssm2Pid>> {
        if (pids.isEmpty()) return emptyList()
        val groups = pids.chunked(TARGET).toMutableList()
        if (groups.size >= 2 && groups.last().size < MIN_TAIL) {
            val tail = groups.removeAt(groups.size - 1)
            groups[groups.size - 1] = groups.last() + tail
        }
        return groups
    }
}
