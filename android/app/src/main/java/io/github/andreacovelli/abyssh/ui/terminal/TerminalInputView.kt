package io.github.andreacovelli.abyssh.ui.terminal

import androidx.core.view.WindowInsetsCompat

import androidx.core.view.WindowCompat

import android.content.ContextWrapper

import android.app.Activity

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.annotation.RequiresApi

/** How the terminal asks the keyboard to treat what is typed. */
enum class TerminalImeMode {
    /** Text without suggestions, which most keyboards take as no autocorrect. */
    Default,

    /** No text at all, for keyboards that autocorrect despite [Default]'s hint. */
    NoAutocorrect,

    /** Text with suggestions, which keyboards need to turn swipes into words. */
    SwipeTyping,
}

fun terminalImeMode(disableAutocorrect: Boolean, swipeTyping: Boolean): TerminalImeMode = when {
    swipeTyping -> TerminalImeMode.SwipeTyping
    disableAutocorrect -> TerminalImeMode.NoAutocorrect
    else -> TerminalImeMode.Default
}

fun terminalImeInputType(mode: TerminalImeMode): Int = when (mode) {
    // TYPE_NULL matches Termux's default and prevents keyboards from treating terminal input as prose.
    TerminalImeMode.NoAutocorrect -> InputType.TYPE_NULL
    TerminalImeMode.Default ->
        InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    TerminalImeMode.SwipeTyping -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
}

private val ENTER_CONFIRMED_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.BOPOMOFO,
)

/**
 * Whether an IME composition of [composing] waits for an Enter to confirm it: Chinese and
 * Japanese input does, while Latin and Korean keyboards commit the text and pass the Enter on.
 */
internal fun needsEnterToConfirm(composing: String): Boolean =
    composing.codePoints().anyMatch { Character.UnicodeScript.of(it) in ENTER_CONFIRMED_SCRIPTS }

/**
 * The length, in chars, of the prefix [a] and [b] share, ending on a code point boundary
 * so a surrogate pair is never split.
 */
internal fun commonCodePointPrefix(a: String, b: String): Int {
    var index = 0
    while (index < a.length && index < b.length) {
        val codepoint = a.codePointAt(index)
        if (codepoint != b.codePointAt(index)) break
        index += Character.charCount(codepoint)
    }
    return index
}

/** Whether a physical keyboard is connected and in use, such as a Bluetooth or cover keyboard. */
internal fun Configuration.hasPhysicalKeyboard(): Boolean =
    hasPhysicalKeyboard(keyboard, hardKeyboardHidden)

internal fun hasPhysicalKeyboard(keyboard: Int, hardKeyboardHidden: Int): Boolean =
    keyboard != Configuration.KEYBOARD_NOKEYS &&
        keyboard != Configuration.KEYBOARD_UNDEFINED &&
        hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
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

    private val deadKeys = DeadKeyComposer()

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

    /** Whether the volume keys send Up and Down instead of changing the volume. */
    var volumeKeysAsArrows = false

    var imeMode = TerminalImeMode.Default
        set(value) {
            if (field == value) return
            field = value
            activeInputConnection?.invalidateImeMirror()
            inputType = terminalImeInputType(value)
            inputMethodManager?.restartInput(this)
        }

    /**
     * Whether the mirror keeps the words already sent. A keyboard with suggestions edits
     * them after the fact, swapping a swiped word for the one picked or undoing an
     * autocorrection, at offsets into the text it remembers. Keyboards without them only
     * edit the open composition, so the mirror drops everything else.
     */
    private val keepsCommittedText: Boolean
        get() = imeMode == TerminalImeMode.SwipeTyping

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

    /**
     * Forgets the text the keyboard typed, after it was used for something other than
     * reaching the terminal as typed: a shortcut took it, or a held modifier changed it.
     * The keyboard would otherwise go on editing words the shell never got.
     */
    fun forgetImeText() {
        activeInputConnection?.invalidateImeMirror()
    }

    private fun emitTerminalText(text: String) {
        activeInputConnection?.clearImeCaretAnchor()
        onTerminalText?.invoke(text)
    }

    private fun emitBackspaceText() {
        activeInputConnection?.clearImeCaretAnchor()
        onTerminalText?.invoke("\u007f")
    }

    private fun emitTerminalTextWithNewlineMapping(text: String) {
        val parts = text.split('\n')
        parts.forEachIndexed { index, part ->
            if (part.isNotEmpty()) {
                emitTerminalText(part)
            }
            if (index < parts.lastIndex) {
                emitTerminalText("\r")
            }
        }
    }

    /** Sends the terminal the edit that turns the mirror's [before] into [after]. */
    private fun emitDiff(before: String, after: String) {
        val commonLen = commonCodePointPrefix(before, after)
        // One Backspace per code point, so an emoji costs one, not one per surrogate.
        val deletes = before.codePointCount(commonLen, before.length)
        val inserted = after.substring(commonLen)
        repeat(deletes) {
            emitBackspaceText()
        }
        if (inserted.isNotEmpty()) {
            emitTerminalTextWithNewlineMapping(inserted)
        }
    }

    /**
     * Whether [event] is an Enter that confirms the open IME composition, as it does on
     * desktop. The composed text has already reached the terminal, so the Enter sends
     * nothing; the next one runs the line. Only Chinese and Japanese compositions take an
     * Enter to confirm: Latin keyboards compose every word, and their Enter runs the line.
     */
    private fun confirmsComposition(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        if (keyCode == compositionEnterKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) compositionEnterKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val start = BaseInputConnection.getComposingSpanStart(editableText)
        val end = BaseInputConnection.getComposingSpanEnd(editableText)
        if (start == -1 || end == -1) return false
        if (!needsEnterToConfirm(editableText.substring(minOf(start, end), maxOf(start, end)))) return false
        activeInputConnection?.invalidateImeMirror()
        compositionEnterKeyCode = keyCode
        return true
    }

    fun armInputSuppression() {
        suppressInput = true
        suppressionSnapshot = editableText.toString()
        suppressionDeadlineUptimeMs = SystemClock.uptimeMillis() + SUPPRESSION_CLEANUP_WINDOW_MS
        // No restartInput: it resets the input mode some keyboards let you pick, such as
        // kana or Latin on Japanese ones. Clearing the mirror reports the empty text instead.
        activeInputConnection?.armSuppression()
    }

    private fun clearSuppression() {
        if (!suppressInput && suppressionSnapshot.isEmpty()) return
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
        inputType = terminalImeInputType(imeMode)
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

    /**
     * Holds a dead key's accent for the next key, and types what they compose. Returns true
     * when the press is used up; a key the accent doesn't combine with goes on as usual.
     */
    private fun composeDeadKey(keyCode: Int, event: KeyEvent): Boolean {
        val result = deadKeys.onKeyDown(
            keyCode = keyCode,
            unicodeChar = event.unicodeChar,
            repeatCount = event.repeatCount,
            modifierKey = KeyEvent.isModifierKey(keyCode),
            chord = event.isCtrlPressed || event.isMetaPressed,
        )
        val text = when (result) {
            DeadKeyResult.Pass -> return false
            is DeadKeyResult.Consume -> result.text
            is DeadKeyResult.Flush -> result.text
        }
        if (text != null) {
            clearSuppression()
            emitTerminalText(text)
        }
        return result is DeadKeyResult.Consume
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        trackCapsLock(event)
        handleShortcutChordDown(keyCode, event)?.let { return it }
        if (confirmsComposition(event)) return true
        if (composeDeadKey(keyCode, event)) return true
        val altGr = altGrText(event)
        if (altGr != null) {
            clearSuppression()
            emitTerminalText(altGr)
            return true
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
            ?: KeyMapper.volumeArrow(keyCode, event.metaState)?.takeIf { volumeKeysAsArrows }
        if (mapped != null && ghosttyAction != null) {
            clearSuppression()
            if (ghosttyAction == GhosttyKeyAction.Press && shouldInvalidateImeMirrorForKey(keyCode)) {
                activeInputConnection?.invalidateImeMirror()
            }
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) {
            clearSuppression()
            // The keyboard's words no longer end the line, so it can't edit them in place.
            if (keepsCommittedText) activeInputConnection?.invalidateImeMirror()
            emitTerminalText(String(Character.toChars(unicodeChar)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        trackCapsLock(event)
        handleShortcutChordUp(keyCode)?.let { return it }
        if (confirmsComposition(event)) return true
        if (deadKeys.onKeyUp(keyCode)) return true
        if (altGrText(event) != null) return true
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
            ?: KeyMapper.volumeArrow(keyCode, event.metaState)?.takeIf { volumeKeysAsArrows }
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
            // A physical keyboard types into the focused view on its own. Asking for the
            // on-screen keyboard anyway leaves an empty strip under the terminal holding
            // the keyboard's hide and switch buttons.
            if (resources.configuration.hasPhysicalKeyboard()) return@post
            // No restartInput here either, so tapping the terminal keeps the input mode.
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
        outAttrs.inputType = terminalImeInputType(imeMode)
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd

        val conn = TerminalInputConnection(this)
        activeInputConnection = conn
        return conn
    }


    private class TerminalInputConnection(
        private val view: TerminalInputView,
    ) : BaseInputConnection(view, true) {

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

        fun clearImeCaretAnchor() {
            imeCaretAnchor = null
        }

        fun armSuppression() {
            clearImeBuffer()
        }

        fun invalidateImeMirror() {
            clearImeBuffer()
        }

        /** Clears the mirror for a change the keyboard didn't make, and tells the keyboard. */
        private fun clearImeBuffer() {
            clearMirrorSilently()
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

        private fun consumeSuppressionIfCleanup(before: String, after: String): Boolean {
            if (!view.isSuppressionCleanupWindowActive()) return false

            val snapshot = view.suppressionSnapshot
            val isCleanupEvent =
                before == after ||
                    after.isEmpty() ||
                    after == snapshot ||
                    (snapshot.startsWith(after) && after.length <= snapshot.length)

            if (!isCleanupEvent) return false

            clearImeBuffer()
            view.clearSuppression()
            return true
        }

        private fun reconcileAndEmitMutation(before: String, after: String) {
            if (consumeSuppressionIfCleanup(before, after)) {
                return
            }

            if (view.suppressInput) {
                view.clearSuppression()
            }

            view.emitDiff(before, after)
            imeCaretAnchor = null

            if (after.contains('\n') || after.contains('\r') || after.length > maxImeBufferChars) {
                clearImeBuffer()
            }
        }

        private fun mutateEditableAndEmit(composing: Boolean, op: () -> Boolean): Boolean {
            val before = getEditable().toString()
            directMutationDepth += 1
            val ok = try {
                op()
            } finally {
                directMutationDepth -= 1
            }
            val after = getEditable().toString()

            if (before != after) {
                outerBatchHandled = true
            }

            reconcileAndEmitMutation(before, after)

            // Drop committed text from the mirror immediately; a long-lived
            // buffer let one backspace diff into many deletes. Keep only an
            // active composing region, until it's committed or finished,
            // unless the keyboard edits committed words.
            if (!composing && !view.keepsCommittedText && getEditable().isNotEmpty()) {
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
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = mutateEditableAndEmit(composing = false) {
                super.commitText(text ?: "", newCursorPosition)
            }

        /**
         * Replaces text in the mirror, as Gboard does to swap a word for a suggestion. A
         * range past the mirror's end is in words the mirror already dropped, after a key
         * that moved the shell's cursor, so where they sit on the shell's line is unknown
         * and they stay as they are.
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?,
        ): Boolean {
            if (maxOf(start, end) > getEditable().length) {
                reportImeSelection()
                return true
            }
            return super.replaceText(start, end, text, newCursorPosition, textAttribute)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Empty composing text ends composition; treat as a commit so the
            // mirror is dropped instead of holding stale state.
            val stillComposing = !text.isNullOrEmpty()
            return mutateEditableAndEmit(composing = stillComposing) {
                super.setComposingText(text ?: "", newCursorPosition)
            }
        }

        override fun finishComposingText(): Boolean {
            imeCaretAnchor = null
            val ok = super.finishComposingText()
            // Composing region is committed; drop the mirror copy so a later
            // backspace can't mass-delete it.
            if (!view.keepsCommittedText && getEditable().isNotEmpty()) clearMirrorSilently()
            return ok
        }

        override fun setSelection(start: Int, end: Int): Boolean {
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
            return extracted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            deleteAndEmit(beforeLength, afterLength, inCodePoints = false) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteAndEmit(beforeLength, afterLength, inCodePoints = true) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        /**
         * Deletes from the mirror and sends the terminal the same deletion. Whatever the
         * mirror can't cover, because the text sits outside it, goes to the terminal as
         * Backspace before the cursor and Delete after it. Deletes are never swallowed: the
         * keyboard only asks for one when the user pressed a key.
         */
        private fun deleteAndEmit(
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
            if (view.suppressInput) view.clearSuppression()
            if (before != after) {
                outerBatchHandled = true
                view.emitDiff(before, after)
            }
            repeat((beforeLength - mirrorBefore).coerceAtLeast(0)) {
                view.emitBackspaceText()
            }
            repeat((afterLength - mirrorAfter).coerceAtLeast(0)) {
                view.emitTerminalText("\u001b[3~")
            }
            reportImeSelection(request = false)
            return ok
        }

        /**
         * Removes the character before the caret from a mirror that keeps committed text,
         * for a Backspace key the terminal gets as a key. The keyboard can then still edit
         * the words before it, as when Backspace undoes an autocorrection. Returns false
         * when the mirror can't follow, and should be cleared instead.
         */
        private fun dropCharBeforeCaret(): Boolean {
            if (!view.keepsCommittedText) return false
            val editable = getEditable()
            val caret = Selection.getSelectionStart(editable)
            if (caret <= 0 || caret != Selection.getSelectionEnd(editable) || getComposingSpanStart(editable) != -1) {
                return false
            }
            val before = editable.toString()
            // A batch around the key must not send the deletion again.
            if (batchEditDepth > 0 && outerBatchBeforeText != before) return false
            imeCaretAnchor = null
            editable.delete(Character.offsetByCodePoints(editable, caret, -1), caret)
            if (batchEditDepth > 0) outerBatchBeforeText = editable.toString()
            reportImeSelection()
            return true
        }

        override fun beginBatchEdit(): Boolean {
            if (batchEditDepth == 0) {
                outerBatchBeforeText = getEditable().toString()
                outerBatchHandled = false
            }
            batchEditDepth += 1
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val ok = super.endBatchEdit()
            val editableAfter = getEditable().toString()

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
                    reconcileAndEmitMutation(batchBefore, editableAfter)
                }
                reportImeSelection(request = false)
            }

            return ok
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (view.confirmsComposition(event)) return true
            val altGr = view.altGrText(event)
            if (altGr != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    view.emitTerminalText(altGr)
                }
                return true
            }
            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState, event.getUnicodeChar(0))
                if (mapped != null) {
                    if (
                        ghosttyAction == GhosttyKeyAction.Press &&
                        view.shouldInvalidateImeMirrorForKey(event.keyCode) &&
                        !(event.keyCode == KeyEvent.KEYCODE_DEL && dropCharBeforeCaret())
                    ) {
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
