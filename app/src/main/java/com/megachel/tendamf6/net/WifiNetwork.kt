package com.megachel.tendamf6.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address

private const val TAG = "TendaWifi"

/** What the phone's Wi-Fi is good for right now. */
sealed interface ModemNetwork {
    /** Wi-Fi is up and the modem's address sits on it. */
    class Available(val network: Network) : ModemNetwork

    /** No Wi-Fi at all — mobile data cannot reach a LAN address. */
    object NoWifi : ModemNetwork

    /** Wi-Fi is up, but it is somebody else's network. */
    object WrongNetwork : ModemNetwork
}

/**
 * Picks the Wi-Fi network to talk to the modem through, and refuses when that
 * network cannot reach it.
 *
 * The refusal matters: on a foreign Wi-Fi the request to 192.168.0.1 leaves
 * through an interface that has no route to it and simply burns the full
 * connect timeout before failing. Observed for real, with the source address in
 * the error: "failed to connect to /192.168.0.1 from /192.168.1.105". Comparing
 * the modem's address against the interface's own subnet catches that in
 * microseconds and needs no location permission, unlike reading the SSID.
 */
fun modemNetwork(context: Context, host: String): ModemNetwork {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return ModemNetwork.NoWifi
    val wifi = pickWifi(cm) ?: return ModemNetwork.NoWifi

    // A hostname instead of an address cannot be checked without resolving it,
    // so do not stand in the way.
    val target = literalIpv4(host) ?: return ModemNetwork.Available(wifi)

    val link = cm.getLinkProperties(wifi) ?: return ModemNetwork.Available(wifi)
    val reaches = link.linkAddresses.any { sameSubnet(it, target) }
    if (!reaches) {
        Log.d(TAG, "$host is off ${link.interfaceName} (${link.linkAddresses})")
    }
    return if (reaches) ModemNetwork.Available(wifi) else ModemNetwork.WrongNetwork
}

/**
 * The active network is the one the system itself would route through, so
 * prefer it when it is Wi-Fi. allNetworks is only a fallback: it can hand back
 * entries that are still listed but no longer usable.
 */
private fun pickWifi(cm: ConnectivityManager): Network? {
    cm.activeNetwork?.let { active ->
        val caps = cm.getNetworkCapabilities(active)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return active
    }

    @Suppress("DEPRECATION")
    return cm.allNetworks.firstOrNull { network ->
        val caps = cm.getNetworkCapabilities(network)
        caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Parses a dotted-quad by hand; InetAddress.getByName would resolve a hostname. */
private fun literalIpv4(host: String): Int? {
    val parts = host.trim().split('.')
    if (parts.size != 4) return null
    var value = 0
    for (part in parts) {
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        value = (value shl 8) or octet
    }
    return value
}

private fun sameSubnet(address: LinkAddress, target: Int): Boolean {
    val local = address.address as? Inet4Address ?: return false
    val prefix = address.prefixLength
    if (prefix !in 1..32) return false
    val mask = if (prefix == 32) -1 else -1 shl (32 - prefix)
    val localValue = local.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
    return (localValue and mask) == (target and mask)
}
