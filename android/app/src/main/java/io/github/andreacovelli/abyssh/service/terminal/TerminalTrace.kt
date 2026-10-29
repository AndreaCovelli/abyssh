package io.github.andreacovelli.abyssh.service.terminal

import android.os.Trace

/**
 * Runs [block] inside a system trace section named [name], so it shows up in
 * Perfetto and systrace captures. Costs almost nothing when no trace is recording.
 */
internal inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
