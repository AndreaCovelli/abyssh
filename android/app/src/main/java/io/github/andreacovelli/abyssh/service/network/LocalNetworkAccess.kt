package io.github.andreacovelli.abyssh.service.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 blocks traffic to the local network until the user grants
 * ACCESS_LOCAL_NETWORK, and a blocked SSH or Mosh connection only times out. This says
 * whether a host is affected, so the app can ask first and fail with a clear message.
 */
class LocalNetworkAccess(context: Context) {
    private val context = context.applicationContext

    /** Whether Android enforces the permission for this app and the user has not granted it. */
    val isRestricted: Boolean
        get() = Build.VERSION.SDK_INT >= LOCAL_NETWORK_API &&
            context.applicationInfo.targetSdkVersion >= LOCAL_NETWORK_API &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    /** Whether Android would block a connection to [host] now. Resolves [host]: call it off the main thread. */
    fun blocks(host: String): Boolean {
        if (!isRestricted || host.isBlank()) return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.any { isLocalNetworkAddress(it) && !routedThroughVpn(it) }
    }

    // Traffic through a VPN, such as Tailscale, is not local network traffic, even to
    // private or 100.64.0.0/10 addresses. Only VPN routes narrower than the default count.
    private fun routedThroughVpn(address: InetAddress): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val isVpn = connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (!isVpn) return false
        val routes = connectivity.getLinkProperties(network)?.routes.orEmpty()
        return routes.any { !it.isDefaultRoute && it.matches(address) }
    }

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, added in API 37. */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val LOCAL_NETWORK_API = 37
    }
}

/**
 * Whether [address] is one Android 17 treats as local network: private, link-local,
 * carrier-grade NAT, unique local IPv6, multicast, or broadcast. Loopback is not.
 */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
    if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return true
    val bytes = address.address
    return when (address) {
        is Inet4Address -> isCarrierGradeNat(bytes) || bytes.all { it == BROADCAST_BYTE }
        is Inet6Address -> bytes[0].toInt() and ULA_MASK == ULA_PREFIX
        else -> false
    }
}

private const val BROADCAST_BYTE = 0xFF.toByte()
private const val ULA_MASK = 0xFE
private const val ULA_PREFIX = 0xFC
private const val CGNAT_FIRST = 100
private const val CGNAT_SECOND_MASK = 0xC0
private const val CGNAT_SECOND = 64

// 100.64.0.0/10
private fun isCarrierGradeNat(bytes: ByteArray): Boolean =
    bytes[0].toInt() and 0xFF == CGNAT_FIRST && bytes[1].toInt() and CGNAT_SECOND_MASK == CGNAT_SECOND
