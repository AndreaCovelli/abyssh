package io.github.andreacovelli.abyssh.ui.theme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.screens.files.CopyIcon
import io.github.andreacovelli.abyssh.ui.screens.files.RefreshIcon
import io.github.andreacovelli.abyssh.ui.screens.files.TerminalIcon
import androidx.compose.ui.Alignment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric + Roborazzi screenshot capture: renders real app Composables with the real
 * theme system on the JVM (no device or emulator) and writes actual PNGs under
 * build/outputs/roborazzi/. See:
 *   app/build/outputs/roborazzi/dark-theme-components.png
 *   app/build/outputs/roborazzi/light-theme-components.png
 *
 * Screenshot testing (compare/verify) can be layered on later via the Roborazzi Gradle
 * plugin's record/verify tasks; for now this just proves the render pipeline works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dark theme renders app components`() {
        capture(themeName = "Catppuccin Mocha", fileName = "dark-theme-components.png")
    }

    @Test
    fun `light theme renders app components`() {
        capture(themeName = "Catppuccin Latte", fileName = "light-theme-components.png")
    }

    private fun capture(themeName: String, fileName: String) {
        val context = ApplicationProvider.getApplicationContext<Application>().applicationContext
        GhosttyThemeRegistry.init(context)

        composeRule.setContent {
            AbyssTheme(themeName = themeName) {
                // Paint the theme background like the app's screen scaffolding does,
                // otherwise the default white window shows through.
                Box(modifier = Modifier.fillMaxSize().background(AbyssColors.current.background)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    AbyssText(
                        text = themeName,
                        style = AbyssTypography.current.title,
                    )
                    AbyssCard {
                        AbyssText(
                            text = "Keep screen awake while connected",
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AbyssButton(onClick = {}) { AbyssText("Connect") }
                        AbyssButton(onClick = {}, variant = AbyssButtonVariant.Outlined) {
                            AbyssText("Disconnect")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AbyssSwitch(checked = true, onCheckedChange = {})
                        AbyssSwitch(checked = false, onCheckedChange = {})
                    }
                    // Settings places switches inside cards, on the surface color.
                    AbyssCard {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AbyssSwitch(checked = true, onCheckedChange = {})
                            AbyssSwitch(checked = false, onCheckedChange = {})
                            AbyssSwitch(checked = false, onCheckedChange = {}, enabled = false)
                        }
                    }
                    // Vector icons, tinted with theme colors.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val colors = AbyssColors.current
                        TerminalIcon(color = colors.success, modifier = Modifier.size(20.dp))
                        RefreshIcon(color = colors.textSecondary, modifier = Modifier.size(18.dp))
                        CopyIcon(color = colors.textMuted, modifier = Modifier.size(16.dp))
                        AbyssCloseButton(onClick = {}, contentDescription = "close")
                    }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$fileName")
    }
}
