package io.github.andreacovelli.abyssh.service.mosh

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Picks the numeric IPv4 or IPv6 address the Mosh UDP client sends to. */
object MoshEndpointResolver {

    /**
     * [connectedAddress] is the peer of the SSH connection that started mosh-server.
     * Mosh uses it when there is one, since a fresh DNS lookup of [host] can return a
     * different machine or address family than the one SSH reached.
     */
    fun resolve(host: String, connectedAddress: String? = null): String =
        resolve(host, connectedAddress, InetAddress::getAllByName)

    internal fun resolve(
        host: String,
        connectedAddress: String? = null,
        lookup: (String) -> Array<InetAddress>,
    ): String {
        if (!connectedAddress.isNullOrBlank()) return connectedAddress
        val address =
            try {
                lookup(host).firstOrNull() ?: throw UnknownHostException(host)
            } catch (error: UnknownHostException) {
                throw IllegalStateException(
                    "Could not resolve Mosh host '$host' to an IP address",
                    error,
                )
            }

        // The port is a separate config field, so IPv6 stays an unbracketed literal. The
        // native client reads a scope only as an interface index (fe80::1%3), not a name.
        val hostAddress = checkNotNull(address.hostAddress) { "Mosh host '$host' has no IP address" }
        val scopeId = (address as? Inet6Address)?.scopeId ?: 0
        val literal = hostAddress.substringBefore('%')
        return if (scopeId != 0) "$literal%$scopeId" else literal
    }
}
