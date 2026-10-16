package com.example.discovery

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    @Volatile
    private var cachedSelfIps: Set<String> = emptySet()
    @Volatile
    private var lastCacheTime: Long = 0

    fun getSelfIps(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < 3000L && cachedSelfIps.isNotEmpty()) {
            return cachedSelfIps
        }
        val set = mutableSetOf(
            "127.0.0.1",
            "::1",
            "localhost",
            "0.0.0.0",
            "10.0.2.15", // Standard Android emulator guest IP
            "10.0.2.16"  // Secondary Android emulator guest IP
        )
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        val host = addr.hostAddress?.substringBefore("%")?.trim()
                        if (!host.isNullOrEmpty()) {
                            set.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Active routable local socket check
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                val local = socket.localAddress?.hostAddress?.substringBefore("%")?.trim()
                if (!local.isNullOrEmpty() && local != "0.0.0.0") {
                    set.add(local)
                }
            }
        } catch (_: Exception) {}

        cachedSelfIps = set
        lastCacheTime = now
        return set
    }

    fun isSelfIp(ip: String): Boolean {
        val clean = ip.substringBefore("%").trim()
        if (clean.isEmpty()) return true
        if (clean == "127.0.0.1" || clean == "::1" || clean == "localhost" || clean == "0.0.0.0") return true
        if (clean == "10.0.2.15" || clean == "10.0.2.16") return true
        return getSelfIps().contains(clean)
    }
}

