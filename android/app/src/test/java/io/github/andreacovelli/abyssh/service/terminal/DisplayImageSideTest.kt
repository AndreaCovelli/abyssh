package io.github.andreacovelli.abyssh.service.terminal

import android.hardware.display.DisplayManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-mdpi")
class DisplayImageSideTest {
    private val displayManager =
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(DisplayManager::class.java)

    @Test
    fun `the longest side of the only display`() {
        assertEquals(1280, longestDisplaySide(displayManager.displays))
    }

    @Test
    fun `a larger second display raises the side`() {
        ShadowDisplayManager.addDisplay("w3840dp-h2160dp-mdpi")

        assertEquals(3840, longestDisplaySide(displayManager.displays))
    }

    @Test
    fun `no displays report no side`() {
        assertEquals(0, longestDisplaySide(emptyArray()))
    }
}
