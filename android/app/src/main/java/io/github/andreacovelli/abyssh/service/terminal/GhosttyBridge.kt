package io.github.andreacovelli.abyssh.service.terminal

import java.nio.ByteBuffer

// One external fun per JNI export in zig-src/src/bridge.
@Suppress("TooManyFunctions")
class GhosttyBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("abyssh_jni")
        }.exceptionOrNull()
    }

    fun nativeStatus(): String = if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }

    fun isLoaded(): Boolean = loadError == null

    external fun nativeVersion(): String
    external fun nativeCreate(cols: Int, rows: Int, maxScrollbackBytes: Int): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeWriteRemote(handle: Long, data: ByteArray)
    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int)
    /**
     * Scrolls by [delta] rows at ([x], [y]): the scrollback, wheel events, or arrow keys,
     * depending on the program. A [momentum] step only scrolls the scrollback. Returns
     * whether anything changed.
     */
    external fun nativeScroll(handle: Long, delta: Int, x: Float, y: Float, momentum: Boolean): Boolean
    external fun nativeScrollToActive(handle: Long): Boolean
    external fun nativeSnapshot(handle: Long, full: Boolean): ByteBuffer
    external fun nativePollTitle(handle: Long): String?
    external fun nativePollPwd(handle: Long): String?
    external fun nativePollClipboard(handle: Long): ByteArray?
    external fun nativeDrainBellCount(handle: Long): Int
    external fun nativeSetColorScheme(handle: Long, scheme: Int)
    external fun nativeSetDefaultColors(
        handle: Long,
        fgRgb: IntArray?,
        bgRgb: IntArray?,
        cursorRgb: IntArray?,
        paletteRgb: ByteArray?,
    )
    external fun nativeEncodeKey(
        handle: Long,
        key: Int,
        cp: Int,
        mods: Int,
        action: Int,
        utf8: String?,
        consumedMods: Int,
    ): ByteArray?
    external fun nativeEncodePaste(handle: Long, data: String): ByteArray?
    external fun nativeSetMouseEncodingSize(
        handle: Long,
        screenWidth: Int,
        screenHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        paddingLeft: Int,
        paddingRight: Int,
    )
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray?
    external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?
    external fun nativeDrainPtyWrites(handle: Long): ByteArray
    // Pixels are left out for the image generations in [cached], whose bitmaps the caller holds.
    external fun nativeSnapshotImages(handle: Long, cached: LongArray): ByteBuffer
    external fun nativeIsImageLoading(handle: Long): Boolean
    // Advances Kitty animations; the milliseconds until the next frame, or -1 when none plays.
    external fun nativeAnimationTick(handle: Long): Long
    // Milliseconds until the app's synchronized output hold times out, or -1 when there is none.
    external fun nativeSyncHoldRemainingMs(handle: Long): Long
    // With rectangle, the two cells are opposite corners of a block selection.
    external fun nativeFormatSelectionRange(handle: Long, startCell: Int, endCell: Int, rectangle: Boolean): String?
    external fun nativeFormatSelectionScreenRange(
        handle: Long,
        startScreenCell: Int,
        endScreenCell: Int,
        rectangle: Boolean,
    ): String?
    external fun nativeSelectWordAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectLineAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectAll(handle: Long): String?
    // Matches of the query in the whole screen, newest first: start row, start col,
    // end row, end col (inclusive) per match, rows counted from the oldest scrollback row.
    external fun nativeSearch(handle: Long, query: String): IntArray
    external fun nativeScrollToScreenRow(handle: Long, screenRow: Int)
    // Scrolls delta OSC 133 prompts up (negative) or down; false when the view stayed put.
    external fun nativeScrollToPrompt(handle: Long, delta: Int): Boolean
    external fun nativeHyperlinkAt(handle: Long, cellX: Int, cellY: Int): String?
}
