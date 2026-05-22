package com.protocol.app.protocol

/**
 * One gauge on the Live Data page. Position is in grid cells, not pixels.
 * width/height are clamped to 1..2 (max 2x2) — the snap grid never produces
 * larger gauges. Grid columns are fixed at [GaugeLayout.columns] = 3.
 */
data class GaugeLayoutEntry(
    val pidId: String,
    val col: Int,
    val row: Int,
    val width: Int = 1,
    val height: Int = 1
)

/**
 * The complete gauge arrangement on the Live Data page. Immutable — every
 * mutation returns a new instance. Placement rules:
 *
 *  - width/height in 1..[MAX_SPAN] (= 2)
 *  - col + width must fit within [columns]
 *  - cells cannot overlap another entry (collision check via [canPlace])
 *
 * The user-visible "2-wide gauge needs room for a 1-wide on the same row"
 * rule is self-satisfying in a 3-column grid with span ≤ 2: two 2-wide
 * gauges can't share a row, so the third column on a 2-wide gauge's row is
 * always either empty or holds a 1-wide.
 */
data class GaugeLayout(
    val entries: List<GaugeLayoutEntry> = emptyList(),
    val columns: Int = DEFAULT_COLUMNS
) {

    val pidIds: Set<String> get() = entries.mapTo(LinkedHashSet()) { it.pidId }

    fun findEntry(pidId: String): GaugeLayoutEntry? = entries.find { it.pidId == pidId }

    fun contains(pidId: String): Boolean = findEntry(pidId) != null

    /** Maximum row index occupied (-1 if empty). */
    fun maxRow(): Int = entries.maxOfOrNull { it.row + it.height - 1 } ?: -1

    /**
     * Collision check: can [width] x [height] be placed at ([col], [row])?
     * Pass [excluding] when checking a resize of an existing entry so the
     * entry's own cells don't block it.
     */
    fun canPlace(
        col: Int,
        row: Int,
        width: Int,
        height: Int,
        excluding: GaugeLayoutEntry? = null
    ): Boolean {
        if (col < 0 || row < 0) return false
        if (width < 1 || height < 1) return false
        if (width > MAX_SPAN || height > MAX_SPAN) return false
        if (col + width > columns) return false
        for (e in entries) {
            if (e === excluding) continue
            if (e.col + e.width <= col) continue
            if (col + width <= e.col) continue
            if (e.row + e.height <= row) continue
            if (row + height <= e.row) continue
            return false
        }
        return true
    }

    /**
     * Scan row-by-row, left-to-right for the first cell that can hold a
     * [width] x [height] gauge. Always returns a valid (col, row); rows
     * grow as needed.
     */
    fun findEmptySlot(width: Int = 1, height: Int = 1): Pair<Int, Int> {
        var row = 0
        while (row < ROW_SEARCH_CAP) {
            for (col in 0..columns - width) {
                if (canPlace(col, row, width, height)) return col to row
            }
            row++
        }
        return 0 to row
    }

    fun withAdded(pidId: String): GaugeLayout {
        if (contains(pidId)) return this
        val (c, r) = findEmptySlot()
        return copy(entries = entries + GaugeLayoutEntry(pidId, c, r))
    }

    fun withRemoved(pidId: String): GaugeLayout =
        copy(entries = entries.filterNot { it.pidId == pidId })

    fun withResized(pidId: String, col: Int, row: Int, width: Int, height: Int): GaugeLayout? {
        val existing = findEntry(pidId) ?: return null
        val w = width.coerceIn(1, MAX_SPAN)
        val h = height.coerceIn(1, MAX_SPAN)
        if (!canPlace(col, row, w, h, excluding = existing)) return null
        return copy(entries = entries.map {
            if (it.pidId == pidId) it.copy(col = col, row = row, width = w, height = h) else it
        })
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val MAX_SPAN = 2
        private const val ROW_SEARCH_CAP = 256
    }
}
