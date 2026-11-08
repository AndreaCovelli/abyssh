package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads the JSON layout that tmux 3.8 prints for `#{window_layout}` and
 * `#{window_visible_layout}`, such as
 * `{"V":2,"L":{"t":"h","w":80,"h":24,"x":0,"y":0,"c":[…]}}`. Each cell has its size in
 * `w` and `h` and its offset in `x` and `y`, and is a pane (`"t":"p"`) with its id in
 * `I`, or holds more cells in `c`, side by side (`"t":"h"`) or stacked (`"t":"v"`).
 * `%layout-change` still reports the older string form, which [TmuxLayout] reads.
 */
internal object TmuxJsonLayout {
    /** The window [layout] describes, or null when it can't be read. */
    fun parse(layout: String, maxDepth: Int, maxCells: Int): TmuxWindowLayout? {
        val root = try {
            Json.parseToJsonElement(layout)
        } catch (_: SerializationException) {
            return null
        }
        val panes = mutableListOf<TmuxPaneRect>()
        val size = Reader(maxDepth, maxCells).cell((root as? JsonObject)?.get("L"), panes, depth = 0) ?: return null
        if (panes.isEmpty()) return null
        return TmuxWindowLayout(size.cols, size.rows, panes)
    }

    private class Reader(private val maxDepth: Int, private val maxCells: Int) {
        /** Reads one cell and the panes inside it into [panes]; returns the cell's size. */
        fun cell(element: JsonElement?, panes: MutableList<TmuxPaneRect>, depth: Int): TmuxPaneSize? {
            // The recursion is bounded so a hostile layout can't overflow the stack.
            if (depth > maxDepth) return null
            val node = element as? JsonObject ?: return null
            val cols = node.dimension("w") ?: return null
            val rows = node.dimension("h") ?: return null
            val x = node.dimension("x") ?: return null
            val y = node.dimension("y") ?: return null
            val read = when (node.text("t")) {
                "p" -> node.text("I")?.let(::paneId)?.let { panes += TmuxPaneRect(it, x, y, cols, rows) } != null
                "h", "v" -> children(node["c"], panes, depth + 1)
                else -> false
            }
            return if (read) TmuxPaneSize(cols, rows) else null
        }

        private fun children(element: JsonElement?, panes: MutableList<TmuxPaneRect>, depth: Int): Boolean {
            val cells = element as? JsonArray ?: return false
            return cells.isNotEmpty() && cells.all { cell(it, panes, depth) != null }
        }

        /** A size or offset in cells, which tmux keeps within its largest window. */
        private fun JsonObject.dimension(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..maxCells }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
