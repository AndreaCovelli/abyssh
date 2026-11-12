package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        connection = checkNotNull(view.onCreateInputConnection(EditorInfo()))
    }

    /** What the shell's line holds after the bytes sent so far, applying Backspace. */
    private fun line(): String = buildString {
        for (char in sent) if (char == BACKSPACE) deleteAt(lastIndex) else append(char)
    }

    private fun StringBuilder.deleteAt(index: Int) {
        if (index >= 0) deleteCharAt(index)
    }

    @Test
    fun `without the on-screen keyboard the view offers no connection and keys still type`() {
        view.usesOnScreenKeyboard = false

        assertFalse(view.onCheckIsTextEditor())
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertEquals(1, keys.size)

        view.usesOnScreenKeyboard = true
        assertTrue(view.onCheckIsTextEditor())
        assertNotNull(view.onCreateInputConnection(EditorInfo()))
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
        view.armInputSuppression()
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
    fun `enter during a latin composition runs the line`() {
        connection.setComposingText("ls", 1)

        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf(GhosttyKey.enter), keys)
        assertEquals("ls", line())
    }

    @Test
    fun `an emoji is sent whole and deleted with one backspace`() {
        connection.setComposingText("a\uD83D\uDE00", 1)
        assertEquals("a\uD83D\uDE00", sent.toString())

        connection.setComposingText("a", 1)
        assertEquals("a\uD83D\uDE00$BACKSPACE", sent.toString())
    }

    @Test
    fun `replacing an emoji with another deletes it whole`() {
        connection.setComposingText("\uD83D\uDE00", 1)
        connection.setComposingText("\uD83D\uDE01", 1)

        assertEquals("\uD83D\uDE00$BACKSPACE\uD83D\uDE01", sent.toString())
    }

    @Test
    fun `volume keys reach the terminal only when set as arrows`() {
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(emptyList<Int>(), keys)

        view.volumeKeysAsArrows = true
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(listOf(TerminalSpecialKey.Down.engineKey), keys)
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

    @Test
    fun `picking a suggestion for a swiped word replaces it`() {
        swipeTyping()
        connection.commitText("hello", 1)
        connection.commitText(" Wii", 1)
        // What Gboard sends when a suggestion is tapped after a swipe.
        connection.beginBatchEdit()
        connection.replaceText(6, 9, "world ", 1, null)
        connection.endBatchEdit()

        assertEquals("hello world ", line())
    }

    @Test
    fun `backspace after a swiped word deletes one character`() {
        swipeTyping()
        connection.commitText("hello", 1)
        connection.deleteSurroundingText(1, 0)

        assertEquals("hell", line())
    }

    @Test
    fun `a suggestion picked after the backspace key replaces the word`() {
        swipeTyping()
        connection.commitText("teh", 1)
        connection.commitText(" ", 1)
        // Gboard deletes the space with the key, then swaps the word it reopened.
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        // The key goes to the terminal as a key, not as text; the shell deletes the space.
        sent.append(BACKSPACE)
        connection.beginBatchEdit()
        connection.replaceText(1, 3, "he ", 1, null)
        connection.endBatchEdit()

        assertEquals("the ", line())
    }

    @Test
    fun `a suggestion for a word the mirror dropped leaves the line alone`() {
        swipeTyping()
        connection.commitText("hello", 1)
        view.armInputSuppression()
        connection.beginBatchEdit()
        connection.replaceText(3, 5, "p ", 1, null)
        connection.endBatchEdit()

        assertEquals("hello", line())
    }

    @Test
    fun `enter ends the words a swipe typing keyboard can edit`() {
        swipeTyping()
        connection.commitText("ls", 1)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        connection.commitText("pwd", 1)

        assertEquals("lspwd", sent.toString())
    }

    @Test
    fun `without swipe typing committed text leaves the mirror`() {
        connection.commitText("hello", 1)

        assertEquals("", view.editableText.toString())
    }

    private fun swipeTyping() {
        view.imeMode = TerminalImeMode.SwipeTyping
        connection = checkNotNull(view.onCreateInputConnection(EditorInfo()))
    }

    private companion object {
        const val BACKSPACE = '\u007f'
        const val FORWARD_DELETE = "\u001b[3~"
        val LEFT = TerminalSpecialKey.Left.engineKey
        val RIGHT = TerminalSpecialKey.Right.engineKey
    }
}
