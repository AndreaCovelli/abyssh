package io.github.andreacovelli.abyssh.ui.terminal

import androidx.core.view.WindowInsetsCompat

import androidx.core.view.WindowCompat

import android.content.ContextWrapper

import android.app.Activity

import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

fun terminalImeInputType(disableAutocorrect: Boolean): Int {
    // TYPE_NULL matches Termux's default and prevents keyboards from treating terminal input as prose.
    if (disableAutocorrect) return InputType.TYPE_NULL

    return InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
        private const val LOG_TAG = "TerminalInput"
        private const val DEBUG_INPUT_LOGS = false
        private const val SUPPRESSION_CLEANUP_WINDOW_MS = 120L
    }

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, Int, Int, Int, Int) -> Unit)? = null

    /** Runs a hardware shortcut chord's key as an Abyssh command; false when none is bound to it. */
    var onShortcutChord: ((Char) -> Boolean)? = null

    /** Starts the shortcut prefix, for Ctrl+Shift+Enter. */
    var onPrefixChord: (() -> Unit)? = null

    /** Reports a physical keyboard's Caps Lock turning on or off. */
    var onCapsLockChanged: ((Boolean) -> Unit)? = null
    private var capsLockOn = false

    // The key of the chord being held and what its press returned, so its repeats and
    // release go to the same place as its press.
    private var chordKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var chordResult = false

    // The Enter that confirmed an IME composition, so its repeats and release stay out
    // of the terminal too.
    private var compositionEnterKeyCode = KeyEvent.KEYCODE_UNKNOWN

    /**
     * When true, suppress IME text input. Used to prevent double-sends
     * when accessory-bar virtual keys like Tab are tapped: we send the
     * key directly via [onTerminalKey], then ignore the IME's follow-up
     * cleanup edits within [SUPPRESSION_CLEANUP_WINDOW_MS].
     */
    @Volatile
    var suppressInput = false

    @Volatile
    private var suppressionSnapshot = ""

    @Volatile
    private var suppressionDeadlineUptimeMs = 0L

    /** Active input connection, used for IME mirror resets. */
    private var activeInputConnection: TerminalInputConnection? = null

    /** Cached InputMethodManager, for selection reports and autocorrect changes. */
    private var inputMethodManager: InputMethodManager? = null

    var disableAutocorrect = false
        set(value) {
            if (field == value) return
            field = value
            inputType = terminalImeInputType(value)
            inputMethodManager?.restartInput(this)
        }

    private fun logInput(message: String) {
        if (!DEBUG_INPUT_LOGS) return
        Log.d(LOG_TAG, message)
    }

    private fun describeText(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '\u001b' -> append("<ESC>")
                '\t' -> append("<TAB>")
                '\r' -> append("<CR>")
                '\n' -> append("<LF>")
                '\u007f' -> append("<BS>")
                else -> append(char)
            }
        }
    }

    /**
     * Keys that should drop the IME mirror buffer when delivered as
     * hardware/Ghostty key events. The terminal handles cursor motion
     * and line editing itself, so the mirror would only get out of sync.
     */
    private fun shouldInvalidateImeMirrorForKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_INSERT ||
            keyCode == KeyEvent.KEYCODE_ESCAPE

    private fun emitTerminalText(source: String, text: String) {
        logInput("emit source=$source text=${describeText(text)}")
        activeInputConnection?.clearImeCaretAnchor()
        onTerminalText?.invoke(text)
    }

    private fun emitBackspaceText(source: String) {
        logInput("emit source=$source text=<BS>")
        activeInputConnection?.clearImeCaretAnchor()
        onTerminalText?.invoke("\u007f")
    }

    private fun emitTerminalTextWithNewlineMapping(source: String, text: String) {
        val parts = text.split('\n')
        parts.forEachIndexed { index, part ->
            if (part.isNotEmpty()) {
                emitTerminalText("$source.part", part)
            }
            if (index < parts.lastIndex) {
                emitTerminalText("$source.newline", "\r")
            }
        }
    }

    /** Sends the terminal the edit that turns the mirror's [before] into [after]. */
    private fun emitDiff(source: String, before: String, after: String) {
        val commonLen = before.zip(after).takeWhile { it.first == it.second }.size
        val deletes = before.length - commonLen
        val inserted = after.substring(commonLen)
        logInput(
            "emitDiff source=$source before=${describeText(before)} after=${describeText(after)} del=$deletes ins=${describeText(inserted)}",
        )
        repeat(deletes) {
            emitBackspaceText("$source.diffDelete")
        }
        if (inserted.isNotEmpty()) {
            emitTerminalTextWithNewlineMapping(source, inserted)
        }
    }

    /**
     * Whether [event] is an Enter that confirms the open IME composition, as it does on
     * desktop. The composed text has already reached the terminal, so the Enter sends
     * nothing; the next one runs the line.
     */
    private fun confirmsComposition(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        if (keyCode == compositionEnterKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) compositionEnterKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (BaseInputConnection.getComposingSpanStart(editableText) == -1) return false
        logInput("confirmsComposition keyCode=$keyCode")
        activeInputConnection?.invalidateImeMirror()
        compositionEnterKeyCode = keyCode
        return true
    }

    fun armInputSuppression(reason: String) {
        suppressInput = true
        suppressionSnapshot = editableText.toString()
        suppressionDeadlineUptimeMs = SystemClock.uptimeMillis() + SUPPRESSION_CLEANUP_WINDOW_MS
        logInput("armSuppression reason=$reason snapshot=${describeText(suppressionSnapshot)}")
        // No restartInput: it resets the input mode some keyboards let you pick, such as
        // kana or Latin on Japanese ones. Clearing the mirror reports the empty text instead.
        activeInputConnection?.armSuppression()
    }

    private fun clearSuppression(reason: String) {
        if (!suppressInput && suppressionSnapshot.isEmpty()) return
        if (suppressInput) {
            logInput("clearSuppression reason=$reason")
        }
        suppressInput = false
        suppressionSnapshot = ""
        suppressionDeadlineUptimeMs = 0L
    }

    private fun isSuppressionCleanupWindowActive(): Boolean =
        suppressInput && SystemClock.uptimeMillis() <= suppressionDeadlineUptimeMs

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        isFocusableInTouchMode = true
        isFocusable = true
        setSingleLine(false)
        imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        inputType = terminalImeInputType(disableAutocorrect)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private fun altGrText(event: KeyEvent): String? = KeyMapper.altGrText(
        metaState = event.metaState,
        unicodeChar = event.unicodeChar,
        plainUnicodeChar = event.getUnicodeChar(event.metaState and KeyEvent.META_ALT_MASK.inv()),
    )

    /**
     * Ctrl+Shift+Enter starts the shortcut prefix. A ⌘ or Ctrl+Shift chord bound to an
     * Abyssh command runs it. Other ⌘ chords return false so the system can still use
     * them, as with ⌘+Tab; other Ctrl+Shift chords return null and reach the terminal.
     */
    private fun handleShortcutChordDown(keyCode: Int, event: KeyEvent): Boolean? {
        if (keyCode == chordKeyCode && event.repeatCount > 0) return chordResult
        val startPrefix = onPrefixChord
        val result = when {
            startPrefix != null && isPrefixChord(keyCode, event.metaState) -> {
                startPrefix()
                true
            }
            shortcutChordKey(event)?.let { onShortcutChord?.invoke(it) } == true -> true
            event.isMetaPressed && !KeyEvent.isModifierKey(keyCode) -> false
            else -> return null
        }
        chordKeyCode = keyCode
        chordResult = result
        return result
    }

    private fun handleShortcutChordUp(keyCode: Int): Boolean? {
        if (keyCode != chordKeyCode) return null
        chordKeyCode = KeyEvent.KEYCODE_UNKNOWN
        return chordResult
    }

    // Soft keyboards don't report their caps lock, and their key events carry none, so
    // only physical keys update it.
    private fun trackCapsLock(event: KeyEvent) {
        if (event.device?.isVirtual != false) return
        val on = event.isCapsLockOn
        if (on == capsLockOn) return
        capsLockOn = on
        onCapsLockChanged?.invoke(on)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logInput(
            "onKeyDown keyCode=$keyCode unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}",
        )
        trackCapsLock(event)
        handleShortcutChordDown(keyCode, event)?.let { return it }
        if (confirmsComposition(event)) return true
        val altGr = altGrText(event)
        if (altGr != null) {
            clearSuppression("onKeyDown.altGr keyCode=$keyCode")
            emitTerminalText("onKeyDown.altGr", altGr)
            return true
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
        if (mapped != null && ghosttyAction != null) {
            clearSuppression("onKeyDown keyCode=$keyCode")
            if (ghosttyAction == GhosttyKeyAction.Press && shouldInvalidateImeMirrorForKey(keyCode)) {
                activeInputConnection?.invalidateImeMirror()
            }
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            clearSuppression("onKeyDown.unicode keyCode=$keyCode")
            emitTerminalText("onKeyDown.unicode", unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        logInput("onKeyUp keyCode=$keyCode flags=${event.flags}")
        trackCapsLock(event)
        handleShortcutChordUp(keyCode)?.let { return it }
        if (confirmsComposition(event)) return true
        if (altGrText(event) != null) return true
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        if (event.unicodeChar != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    fun showKeyboard(imm: InputMethodManager?) {
        if (imm == null) return
        inputMethodManager = imm
        if (!hasFocus()) {
            requestFocus()
            requestFocusFromTouch()
        }
        post {
            // No restartInput here either, so tapping the terminal keeps the input mode.
            logInput("showKeyboard")
            // The insets controller replaces showSoftInput(SHOW_IMPLICIT), deprecated in API 37.
            val window = context.findActivity()?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, this).show(WindowInsetsCompat.Type.ime())
            } else {
                imm.showSoftInput(this, 0)
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        if (inputMethodManager == null) {
            inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = terminalImeInputType(disableAutocorrect)
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd

        val conn = TerminalInputConnection(this)
        activeInputConnection = conn
        logInput("onCreateInputConnection conn=${conn.connectionId}")
        return conn
    }

    private class TerminalInputConnection(
        private val view: TerminalInputView,
    ) : BaseInputConnection(view, true) {

        val connectionId: Int = System.identityHashCode(this)

        private val maxImeBufferChars = 1024
        private val maxImeCursorMoveSteps = 32

        private var batchEditDepth = 0
        private var outerBatchBeforeText: String? = null
        // Whether the outer batch's change already reached the terminal, or was only a
        // mirror clear, so endBatchEdit must not send it again.
        private var outerBatchHandled = false
        private var directMutationDepth = 0
        private var imeCaretAnchor: Int? = null
        private var selectionReportPending = false

        override fun getEditable(): Editable = view.editableText

        private fun logConn(message: String) {
            view.logInput("conn=$connectionId $message")
        }

        fun clearImeCaretAnchor() {
            imeCaretAnchor = null
        }

        fun armSuppression() {
            logConn("armSuppression -> clearImeBuffer")
            clearImeBuffer()
        }

        fun invalidateImeMirror() {
            logConn("invalidateImeMirror -> clearImeBuffer")
            clearImeBuffer()
        }

        /** Clears the mirror for a change the keyboard didn't make, and tells the keyboard. */
        private fun clearImeBuffer() {
            clearMirrorSilently()
            logConn("clearImeBuffer")
            reportImeSelection()
        }

        /**
         * Tells the keyboard the mirror's selection and composing region, once no batch or
         * edit is in progress. [request] false only sends a report that was put off.
         */
        private fun reportImeSelection(request: Boolean = true) {
            if (request) selectionReportPending = true
            if (!selectionReportPending || batchEditDepth > 0 || directMutationDepth > 0) return
            selectionReportPending = false
            val editable = getEditable()
            view.inputMethodManager?.updateSelection(
                view,
                Selection.getSelectionStart(editable).coerceAtLeast(0),
                Selection.getSelectionEnd(editable).coerceAtLeast(0),
                BaseInputConnection.getComposingSpanStart(editable),
                BaseInputConnection.getComposingSpanEnd(editable),
            )
        }

        private fun consumeSuppressionIfCleanup(source: String, before: String, after: String): Boolean {
            if (!view.isSuppressionCleanupWindowActive()) return false

            val snapshot = view.suppressionSnapshot
            val isCleanupEvent =
                before == after ||
                    after.isEmpty() ||
                    after == snapshot ||
                    (snapshot.startsWith(after) && after.length <= snapshot.length)

            if (!isCleanupEvent) return false

            logConn(
                "consumeSuppressionIfCleanup source=$source before=${view.describeText(before)} after=${view.describeText(after)} snapshot=${view.describeText(snapshot)}",
            )
            clearImeBuffer()
            view.clearSuppression("$source cleanup")
            return true
        }

        private fun reconcileAndEmitMutation(source: String, before: String, after: String) {
            if (consumeSuppressionIfCleanup(source, before, after)) {
                return
            }

            if (view.suppressInput) {
                view.clearSuppression("$source real input")
            }

            view.emitDiff(source, before, after)
            imeCaretAnchor = null

            if (after.contains('\n') || after.contains('\r') || after.length > maxImeBufferChars) {
                clearImeBuffer()
            }
        }

        private fun mutateEditableAndEmit(source: String, composing: Boolean, op: () -> Boolean): Boolean {
            val before = getEditable().toString()
            directMutationDepth += 1
            val ok = try {
                op()
            } finally {
                directMutationDepth -= 1
            }
            val after = getEditable().toString()

            logConn(
                "mutate source=$source composing=$composing ok=$ok before=${view.describeText(before)} after=${view.describeText(after)} suppress=${view.suppressInput}",
            )

            if (before != after) {
                outerBatchHandled = true
            }

            reconcileAndEmitMutation(source, before, after)

            // Drop committed text from the mirror immediately; a long-lived
            // buffer let one backspace diff into many deletes. Keep only an
            // active composing region, until it's committed or finished.
            if (!composing && getEditable().isNotEmpty()) {
                clearMirrorSilently()
            }
            reportImeSelection(request = false)
            return ok
        }

        // Clear the mirror and composing spans without emitting bytes, keeping
        // it empty between commits so a later delete can't over-emit backspaces.
        private fun clearMirrorSilently() {
            imeCaretAnchor = null
            val editable = getEditable()
            BaseInputConnection.removeComposingSpans(editable)
            if (editable.isNotEmpty()) {
                // Bookkeeping, not a deletion: the text already reached the terminal. A
                // batch closing around it, as when a composition is confirmed, must not
                // read the clear as text to delete.
                outerBatchHandled = true
                editable.clear()
            }
            Selection.setSelection(editable, 0)
            logConn("clearMirrorSilently")
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            logConn(
                "commitText text=${view.describeText((text ?: "").toString())} cursor=$newCursorPosition",
            )
            return mutateEditableAndEmit("commitText", composing = false) {
                super.commitText(text ?: "", newCursorPosition)
            }
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            logConn(
                "setComposingText text=${view.describeText((text ?: "").toString())} cursor=$newCursorPosition",
            )
            // Empty composing text ends composition; treat as a commit so the
            // mirror is dropped instead of holding stale state.
            val stillComposing = !text.isNullOrEmpty()
            return mutateEditableAndEmit("setComposingText", composing = stillComposing) {
                super.setComposingText(text ?: "", newCursorPosition)
            }
        }

        override fun finishComposingText(): Boolean {
            logConn("finishComposingText")
            imeCaretAnchor = null
            val ok = super.finishComposingText()
            // Composing region is committed; drop the mirror copy so a later
            // backspace can't mass-delete it.
            if (getEditable().isNotEmpty()) clearMirrorSilently()
            return ok
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            logConn("setSelection start=$start end=$end")
            if (
                start != end ||
                BaseInputConnection.getComposingSpanStart(getEditable()) != -1 ||
                directMutationDepth > 0
            ) {
                imeCaretAnchor = null
                return super.setSelection(start, end)
            }

            val anchor = imeCaretAnchor
            if (anchor == null || batchEditDepth > 0) {
                // Inside a batch the keyboard places the caret for an edit it is making,
                // such as an undo, not to move it, so only re-anchor.
                imeCaretAnchor = start
            } else {
                val delta = start.toLong() - anchor.toLong()
                val steps = kotlin.math.abs(delta)
                if (steps <= maxImeCursorMoveSteps) {
                    imeCaretAnchor = start
                    emitImeCursorMove(
                        if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
                        steps.toInt(),
                    )
                } else {
                    // Past the cap the two models have diverged. Emit nothing and
                    // drop the anchor, so the next selection re-anchors instead of
                    // diffing against a position the terminal never reached.
                    imeCaretAnchor = null
                }
            }
            return super.setSelection(start, end)
        }

        private fun emitImeCursorMove(keyCode: Int, steps: Int) {
            val mapped = KeyMapper.map(keyCode, 0, 0) ?: return
            repeat(steps) {
                view.onTerminalKey?.invoke(
                    mapped.key,
                    mapped.codepoint,
                    mapped.mods,
                    GhosttyKeyAction.Press,
                    mapped.charCode,
                )
                view.onTerminalKey?.invoke(
                    mapped.key,
                    mapped.codepoint,
                    mapped.mods,
                    GhosttyKeyAction.Release,
                    mapped.charCode,
                )
            }
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val editable = getEditable()
            val extracted = ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
                selectionEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
            }
            logConn(
                "getExtractedText flags=$flags text=${view.describeText(extracted.text.toString())} sel=${extracted.selectionStart}..${extracted.selectionEnd}",
            )
            return extracted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            deleteAndEmit("deleteSurroundingText", beforeLength, afterLength, inCodePoints = false) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteAndEmit("deleteSurroundingTextInCodePoints", beforeLength, afterLength, inCodePoints = true) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        /**
         * Deletes from the mirror and sends the terminal the same deletion. Whatever the
         * mirror can't cover, because the text sits outside it, goes to the terminal as
         * Backspace before the cursor and Delete after it. Deletes are never swallowed: the
         * keyboard only asks for one when the user pressed a key.
         */
        private fun deleteAndEmit(
            source: String,
            beforeLength: Int,
            afterLength: Int,
            inCodePoints: Boolean,
            op: () -> Boolean,
        ): Boolean {
            imeCaretAnchor = null
            if (beforeLength < 0 || afterLength < 0) return false
            val editable = getEditable()
            // BaseInputConnection deletes around an open composition, but the shell can only
            // delete at its cursor, after the composed text. Confirm the composition so both
            // count from the caret.
            if (getComposingSpanStart(editable) != -1) removeComposingSpans(editable)
            val before = editable.toString()
            val caretStart = minOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))
                .coerceIn(0, before.length)
            val caretEnd = maxOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))
                .coerceIn(caretStart, before.length)
            // How much of the request the mirror holds, in the request's own unit.
            val mirrorBefore = if (inCodePoints) before.codePointCount(0, caretStart) else caretStart
            val mirrorAfter = if (inCodePoints) before.codePointCount(caretEnd, before.length) else before.length - caretEnd
            directMutationDepth += 1
            val ok = try {
                op()
            } finally {
                directMutationDepth -= 1
            }
            val after = editable.toString()
            logConn(
                "$source beforeLen=$beforeLength afterLen=$afterLength ok=$ok before=${view.describeText(before)} after=${view.describeText(after)}",
            )
            if (view.suppressInput) view.clearSuppression("$source real input")
            if (before != after) {
                outerBatchHandled = true
                view.emitDiff(source, before, after)
            }
            repeat((beforeLength - mirrorBefore).coerceAtLeast(0)) {
                view.emitBackspaceText("$source.before")
            }
            repeat((afterLength - mirrorAfter).coerceAtLeast(0)) {
                view.emitTerminalText("$source.after", "\u001b[3~")
            }
            reportImeSelection(request = false)
            return ok
        }

        override fun beginBatchEdit(): Boolean {
            if (batchEditDepth == 0) {
                outerBatchBeforeText = getEditable().toString()
                outerBatchHandled = false
            }
            batchEditDepth += 1
            logConn("beginBatchEdit depth=$batchEditDepth")
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val ok = super.endBatchEdit()
            val editableAfter = getEditable().toString()
            logConn("endBatchEdit ok=$ok depth=$batchEditDepth editable=${view.describeText(editableAfter)}")

            if (batchEditDepth > 0) {
                batchEditDepth -= 1
            }

            if (batchEditDepth == 0) {
                val batchBefore = outerBatchBeforeText
                outerBatchBeforeText = null
                val handled = outerBatchHandled
                outerBatchHandled = false

                if (directMutationDepth > 0 && batchBefore != editableAfter) {
                    // This outer batch is closing inside a direct mutation callback
                    // (commitText/setComposingText/delete). That callback will emit
                    // the diff once it regains control after super.* returns.
                    outerBatchHandled = true
                }

                if (batchBefore != null && batchBefore != editableAfter && !handled && directMutationDepth == 0) {
                    logConn(
                        "endBatchEdit reconcile before=${view.describeText(batchBefore)} after=${view.describeText(editableAfter)}",
                    )
                    reconcileAndEmitMutation("endBatchEdit", batchBefore, editableAfter)
                }
                reportImeSelection(request = false)
            }

            return ok
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            logConn(
                "sendKeyEvent action=${event.action} keyCode=${event.keyCode} unicode=${event.unicodeChar} meta=${event.metaState} flags=${event.flags}",
            )
            if (view.confirmsComposition(event)) return true
            val altGr = view.altGrText(event)
            if (altGr != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    view.emitTerminalText("sendKeyEvent.altGr", altGr)
                }
                return true
            }
            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
                if (mapped != null) {
                    if (ghosttyAction == GhosttyKeyAction.Press && view.shouldInvalidateImeMirrorForKey(event.keyCode)) {
                        invalidateImeMirror()
                    }
                    view.onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
