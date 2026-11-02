package io.github.andreacovelli.abyssh.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/** Waits until the device has a network to connect over. */
fun interface NetworkWaiter {
    /** Returns once a network is available, at once when one already is. */
    suspend fun awaitNetwork()

    companion object {
        /** Never waits, for code that has no [Context], such as unit tests. */
        val None = NetworkWaiter {}
    }
}

/**
 * Waits for a default network that offers internet access, without requiring Android to
 * have validated it, so a LAN-only Wi-Fi or a VPN still counts. Reconnects wait here
 * instead of failing at once while the device is offline.
 */
class NetworkAvailability(context: Context) : NetworkWaiter {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override suspend fun awaitNetwork() {
        val connectivity = connectivity ?: return
        if (connectivity.hasInternet(connectivity.activeNetwork)) return
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(connectivity.hasInternet(network))
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            // Android limits how many callbacks an app registers; when it refuses one,
            // connect anyway, as before there was a wait.
            val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
            if (!registered) trySend(true)
            awaitClose {
                if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }.first { it }
    }
}

private fun ConnectivityManager.hasInternet(network: Network?): Boolean =
    network != null && getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
