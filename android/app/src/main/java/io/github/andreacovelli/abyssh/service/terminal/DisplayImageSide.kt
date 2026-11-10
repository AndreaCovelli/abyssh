package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/** The longest side, in pixels, of any mode of [displays], or 0 when none reports a size. */
internal fun longestDisplaySide(displays: Array<Display>): Int =
    displays.maxOfOrNull { display ->
        display.supportedModes.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
    } ?: 0

/**
 * Tells the native terminal the longest side of the connected displays now and whenever
 * one is added, removed, or changed, so Kitty images are stored no larger than a screen
 * can show them. Images already stored keep their size.
 */
internal fun followLongestDisplaySide(context: Context, bridge: GhosttyBridge) {
    if (!bridge.isLoaded()) return
    val manager = context.getSystemService(DisplayManager::class.java) ?: return
    val update = { bridge.nativeSetDisplaySide(longestDisplaySide(manager.displays)) }
    manager.registerDisplayListener(
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()

            override fun onDisplayRemoved(displayId: Int) = update()

            override fun onDisplayChanged(displayId: Int) = update()
        },
        Handler(Looper.getMainLooper()),
    )
    update()
}
