package io.github.andreacovelli.abyssh.ui.terminal

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lays out the accessory bar with real keys: how they squeeze, wrap, and stack. It also
 * writes each bar to build/outputs/roborazzi/ when run with `-Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardAccessoryBarLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Eight keys, the last four of the least width. Each takes about 46 dp with the gap after it.
    private val labels = listOf("Esc", "Tab", "Ctrl", "Alt", "F1", "F2", "-", "/")
    private val entries = TerminalAccessoryLayoutStore.resolveSelectedLayout(
        listOf("escape", "tab", "ctrl", "alt", "f1", "f2", "minus", "slash"),
    )

    @Before
    fun setUp() {
        GhosttyThemeRegistry.init(ApplicationProvider.getApplicationContext<Application>().applicationContext)
    }

    private fun show(
        width: Dp,
        height: Dp? = null,
        position: AccessoryBarPosition = AccessoryBarPosition.Bottom,
        singleRow: Boolean = false,
        name: String,
    ) {
        composeRule.setContent {
            AbyssTheme(themeName = "Catppuccin Mocha") {
                val size = if (height != null) Modifier.width(width).height(height) else Modifier.width(width)
                Box(size.background(AbyssColors.current.background).testTag("bar")) {
                    KeyboardAccessoryBar(
                        entries = entries,
                        modifierState = ModifierState(),
                        onAction = {},
                        useSingleRow = singleRow,
                        position = position,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("bar").captureRoboImage("build/outputs/roborazzi/accessory-bar-$name.png")
    }

    private fun bounds(): List<DpRect> = labels.map { composeRule.onNodeWithText(it).getBoundsInRoot() }

    @Test
    fun `keys that fit keep their size`() {
        show(width = 400.dp, name = "fits")

        val keys = bounds()
        assertEquals(1, keys.map { it.top }.distinct().size)
        assertEquals(46.dp.value, (keys[5].left - keys[4].left).value, 1f)
    }

    @Test
    fun `keys that almost fit squeeze onto one row`() {
        show(width = 330.dp, name = "squeezed")

        val keys = bounds()
        assertEquals(1, keys.map { it.top }.distinct().size)
        assertTrue(keys.last().right <= 322.dp)
        // Squeezed only as far as it takes, so the row is full.
        assertTrue(keys.last().right > 318.dp)
        val pitch = keys[5].left - keys[4].left
        assertTrue("pitch $pitch", pitch < 46.dp && pitch > 31.dp)
    }

    @Test
    fun `keys that don't fit even squeezed keep their size and wrap`() {
        show(width = 240.dp, name = "wrapped")

        val keys = bounds()
        assertEquals(2, keys.map { it.top }.distinct().size)
        assertEquals(46.dp.value, (keys[5].left - keys[4].left).value, 1f)
        assertTrue(keys.all { it.right <= 232.dp || it.top > keys[0].top })
    }

    @Test
    fun `a single row keeps its keys on one line when they don't fit`() {
        show(width = 240.dp, singleRow = true, name = "single-row")

        // The keys past the end of the row are out of view until it scrolls.
        val keys = bounds().take(4)
        assertEquals(1, keys.map { it.top }.distinct().size)
        assertEquals(46.dp.value, (keys[3].right - keys[2].right).value, 1f)
        composeRule.onNodeWithText("/").assertIsNotDisplayed()
    }

    @Test
    fun `a bar on a side stacks keys of one width`() {
        show(width = 200.dp, height = 400.dp, position = AccessoryBarPosition.Left, name = "left")

        val keys = bounds()
        assertEquals(1, keys.map { it.left }.distinct().size)
        assertEquals(1, keys.map { it.width }.distinct().size)
        assertEquals(36.dp.value, (keys[1].top - keys[0].top).value, 1f)
        assertTrue(keys[0].right < 60.dp)
    }

    @Test
    fun `a bar on a side squeezes its keys in height before it takes a second column`() {
        show(width = 200.dp, height = 250.dp, position = AccessoryBarPosition.Right, name = "right-squeezed")

        val squeezed = bounds()
        assertEquals(1, squeezed.map { it.left }.distinct().size)
        val pitch = squeezed[1].top - squeezed[0].top
        assertTrue("pitch $pitch", pitch < 36.dp && pitch >= 27.dp)
    }

    @Test
    fun `a bar on a side too short for its keys takes a second column`() {
        show(width = 200.dp, height = 160.dp, position = AccessoryBarPosition.Left, name = "left-wrapped")

        val keys = bounds()
        assertEquals(2, keys.map { it.left }.distinct().size)
        assertEquals(36.dp.value, (keys[1].top - keys[0].top).value, 1f)
    }
}

private val DpRect.width: Dp get() = right - left
