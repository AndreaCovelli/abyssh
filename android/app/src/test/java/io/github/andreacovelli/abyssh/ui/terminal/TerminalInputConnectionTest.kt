package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalInputConnectionTest {

    private lateinit var view: TerminalInputView
    private lateinit var connection: InputConnection
    private val sent = StringBuilder()
    private val keys = mutableListOf<Int>()

    @Before
    fun setUp() {
        view = TerminalInputView(ApplicationProvider.getApplicationContext())
        view.onTerminalText = { sent.append(it) }
        view.onTerminalKey = { key, _, _, action, _ -> if (action == GhosttyKeyAction.Press) keys += key }
        connection = view.onCreateInputConnection(EditorInfo())
    }

    /** What the shell's line holds after the bytes sent so far, applying Backspace. */
    private fun line(): String = buildString {
        for (char in sent) if (char == BACKSPACE) deleteAt(lastIndex) else append(char)
    }

    private fun StringBuilder.deleteAt(index: Int) {
        if (index >= 0) deleteCharAt(index)
    }

    @Test
    fun `confirming a composition in a batch keeps its text`() {
        connection.setComposingText("日本語", 1)
        connection.beginBatchEdit()
        connection.finishComposingText()
        connection.endBatchEdit()

        assertEquals("日本語", line())
        assertEquals("日本語", sent.toString())
    }

    @Test
    fun `committing the composed text unchanged in a batch sends nothing more`() {
        connection.setComposingText("にほん", 1)
        connection.beginBatchEdit()
        connection.commitText("にほん", 1)
        connection.endBatchEdit()

        assertEquals("にほん", sent.toString())
    }

    @Test
    fun `a converted composition replaces the reading`() {
        connection.setComposingText("にほん", 1)
        connection.beginBatchEdit()
        connection.commitText("日本", 1)
        connection.endBatchEdit()

        assertEquals("日本", line())
    }

    @Test
    fun `backspace right after an accessory key still reaches the shell`() {
        view.armInputSuppression("Tab")
        connection.deleteSurroundingText(1, 0)

        assertEquals(BACKSPACE.toString(), sent.toString())
    }

    @Test
    fun `code point deletes reach the shell`() {
        connection.deleteSurroundingTextInCodePoints(2, 0)

        assertEquals("$BACKSPACE$BACKSPACE", sent.toString())
    }

    @Test
    fun `forward delete during a composition reaches the shell`() {
        connection.setComposingText("ab", 1)
        connection.deleteSurroundingText(0, 1)

        assertEquals("ab$FORWARD_DELETE", sent.toString())
    }

    @Test
    fun `a delete wider than the composition deletes past it`() {
        connection.setComposingText("ab", 1)
        connection.deleteSurroundingText(3, 1)

        assertEquals("ab$BACKSPACE$BACKSPACE$BACKSPACE$FORWARD_DELETE", sent.toString())
    }

    @Test
    fun `enter during a composition confirms it and the next enter runs the line`() {
        connection.setComposingText("にほん", 1)

        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(emptyList<Int>(), keys)

        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf(GhosttyKey.enter), keys)
        assertEquals("にほん", line())
    }

    @Test
    fun `caret moves outside a batch become arrow keys`() {
        connection.setSelection(5, 5)
        connection.setSelection(3, 3)

        assertEquals(listOf(LEFT, LEFT), keys)
    }

    @Test
    fun `a caret placed inside a batch only re-anchors`() {
        connection.setSelection(5, 5)
        connection.beginBatchEdit()
        connection.setSelection(2, 2)
        connection.endBatchEdit()
        connection.setSelection(3, 3)

        assertEquals(listOf(RIGHT), keys)
    }

    private companion object {
        const val BACKSPACE = '\u007f'
        const val FORWARD_DELETE = "\u001b[3~"
        val LEFT = TerminalSpecialKey.Left.engineKey
        val RIGHT = TerminalSpecialKey.Right.engineKey
    }
}
