package io.github.andreacovelli.abyssh.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkAvailabilityTest {
    private lateinit var context: Context
    private lateinit var connectivity: ConnectivityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectivity = context.getSystemService(ConnectivityManager::class.java)
    }

    private fun capabilities(internet: Boolean): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        if (internet) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return capabilities
    }

    private val callbacks get() = shadowOf(connectivity).networkCallbacks

    @Test
    fun returnsAtOnceWhenTheActiveNetworkHasInternet() = runTest {
        val active = checkNotNull(connectivity.activeNetwork)
        shadowOf(connectivity).setNetworkCapabilities(active, capabilities(internet = true))

        NetworkAvailability(context).awaitNetwork()

        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun waitsOfflineUntilANetworkWithInternetAppears() = runTest {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        var returned = false
        launch {
            NetworkAvailability(context).awaitNetwork()
            returned = true
        }
        runCurrent()
        assertFalse(returned)
        assertEquals(1, callbacks.size)

        // A network without internet access, such as a captive setup network, doesn't count.
        val network = ShadowNetwork.newInstance(7)
        callbacks.single().onCapabilitiesChanged(network, capabilities(internet = false))
        runCurrent()
        assertFalse(returned)

        // Internet access counts even before Android validates it, as on a LAN-only Wi-Fi.
        callbacks.single().onCapabilitiesChanged(network, capabilities(internet = true))
        runCurrent()
        assertTrue(returned)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun cancellingTheWaitUnregistersTheCallback() = runTest {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        val wait = launch { NetworkAvailability(context).awaitNetwork() }
        runCurrent()
        assertEquals(1, callbacks.size)

        wait.cancel()
        runCurrent()

        assertTrue(callbacks.isEmpty())
    }
}
