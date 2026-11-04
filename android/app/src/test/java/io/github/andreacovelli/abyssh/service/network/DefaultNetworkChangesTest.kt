package io.github.andreacovelli.abyssh.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNetworkChangesTest {
    @Test
    fun theFirstNetworkIsNotAChange() {
        assertFalse(DefaultNetworkChanges().update("wifi", setOf("10.0.0.2")))
    }

    @Test
    fun anotherNetworkIsAChange() {
        val changes = DefaultNetworkChanges()
        changes.update("wifi", setOf("10.0.0.2"))

        assertTrue(changes.update("cellular", setOf("100.64.0.9")))
        assertFalse(changes.update("cellular", setOf("100.64.0.9")))
    }

    @Test
    fun newAddressesOnTheSameNetworkAreAChange() {
        val changes = DefaultNetworkChanges()
        changes.update("wifi", setOf("10.0.0.2"))

        assertTrue(changes.update("wifi", setOf("10.0.0.2", "fd00::2")))
        assertTrue(changes.update("wifi", setOf("fd00::2")))
    }

    @Test
    fun theFlowEmitsOnAChangeAndUnregistersWhenCancelled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(connectivity).networkCallbacks
        var emitted = 0
        val collector = launch { defaultNetworkChanges(context).collect { emitted++ } }
        runCurrent()
        assertEquals(1, callbacks.size)

        callbacks.single().onLinkPropertiesChanged(ShadowNetwork.newInstance(1), LinkProperties())
        runCurrent()
        assertEquals(0, emitted)

        callbacks.single().onLinkPropertiesChanged(ShadowNetwork.newInstance(2), LinkProperties())
        runCurrent()
        assertEquals(1, emitted)

        collector.cancel()
        runCurrent()
        assertTrue(callbacks.isEmpty())
    }
}
