package com.example.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.model.TvBrand
import com.example.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TvDiscoveryManager(private val context: Context) {

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val seenDevices = ConcurrentHashMap<String, TvDevice>()
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveMutex = Mutex()
    private val resolveQueue = Channel<NsdServiceInfo>(capacity = 64)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun clearDiscovered() {
        seenDevices.clear()
        _discoveredDevices.value = emptyList()
    }

    fun startScanning(scope: CoroutineScope, clearPrevious: Boolean = false) {
        if (clearPrevious) {
            clearDiscovered()
        }
        stopScanning()

        // Acquire Wi-Fi Multicast Lock to allow receiving UDP multicast packets
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("tactile_discovery_multicast")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        scanJob = scope.launch(Dispatchers.IO) {
            launch { processResolveQueue() }
            launch { runSsdpScan() }
            launch { runNsdDiscovery() }
            launch { runDirectMdnsQuery() }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsdDiscoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (_: Exception) {}
        }
        nsdDiscoveryListener = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private suspend fun processResolveQueue() {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        for (serviceInfo in resolveQueue) {
            resolveMutex.withLock {
                try {
                    resolveServiceWithTimeout(nsdManager, serviceInfo)
                } catch (e: Exception) {
                    Log.w(TAG, "Resolve error: ${e.message}")
                }
            }
            delay(50) // Small yield between resolves to prevent Android NsdManager internal collision
        }
    }

    private suspend fun resolveServiceWithTimeout(nsdManager: NsdManager, serviceInfo: NsdServiceInfo) {
        withContext(Dispatchers.IO) {
            // Guard with timeout so an unresponsive resolve never blocks the channel
            val resolved = withTimeoutOrNull(2500L) {
                kotlinx.coroutines.suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
                                if (cont.isActive) cont.resume(null, null)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(resolved, null)
                            }
                        })
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null, null)
                    }
                }
            }

            if (resolved != null) {
                val rawHost = resolved.host?.hostAddress
                if (!rawHost.isNullOrEmpty()) {
                    val host = rawHost.substringBefore("%") // Strip interface suffix if present
                    if (host.startsWith("fe80", ignoreCase = true)) {
                        return@withContext
                    }
                    val rawName = resolved.serviceName.ifEmpty { "Android TV" }
                    val cleanName = rawName
                        .replace("_androidtvremote2", "", ignoreCase = true)
                        .replace("._tcp", "", ignoreCase = true)
                        .replace(".local", "", ignoreCase = true)
                        .trim()
                        .ifEmpty { "Android TV" }

                    emitDevice(
                        TvDevice(
                            id = "atv_$host",
                            name = cleanName,
                            ip = host,
                            brand = TvBrand.ANDROID_TV,
                            port = if (resolved.port > 0) resolved.port else 6466
                        )
                    )
                }
            }
        }
    }

    /**
     * SSDP Discovery for LG webOS TVs:
     * Broadcasts M-SEARCH queries for webOS second-screen and media renderer targets.
     */
    private suspend fun runSsdpScan() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 2500
                broadcast = true
                bind(InetSocketAddress(0))
            }

            val targets = listOf(
                "urn:lge-com:service:webos-second-screen:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "ssdp:all"
            )

            // Broadcast M-SEARCH 3 times across multicast and local broadcast addresses
            launch {
                val broadcastAddresses = getBroadcastAddresses()
                for (attempt in 0 until 4) {
                    if (!isActive) break
                    for (st in targets) {
                        val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\n" +
                                "ST: $st\r\n\r\n"

                        val ssdpBytes = ssdpMessage.toByteArray(Charsets.UTF_8)

                        // 1. Multicast group
                        try {
                            val packet = DatagramPacket(
                                ssdpBytes,
                                ssdpBytes.size,
                                InetAddress.getByName("239.255.255.250"),
                                1900
                            )
                            socket.send(packet)
                        } catch (_: Exception) {}

                        // 2. Global broadcast
                        try {
                            val packet = DatagramPacket(
                                ssdpBytes,
                                ssdpBytes.size,
                                InetAddress.getByName("255.255.255.255"),
                                1900
                            )
                            socket.send(packet)
                        } catch (_: Exception) {}

                        // 3. Subnet broadcast addresses
                        for (bcast in broadcastAddresses) {
                            try {
                                val packet = DatagramPacket(ssdpBytes, ssdpBytes.size, bcast, 1900)
                                socket.send(packet)
                            } catch (_: Exception) {}
                        }
                    }
                    delay(600)
                }
            }

            val buffer = ByteArray(8192)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            while (isActive && (System.currentTimeMillis() - startTime) < 14000) {
                try {
                    // CRITICAL: Reset packet length before each receive to prevent packet truncation
                    receivePacket.length = buffer.size
                    socket.receive(receivePacket)

                    val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    val rawIp = receivePacket.address.hostAddress ?: continue
                    val ip = rawIp.substringBefore("%")
                    if (ip.startsWith("fe80", ignoreCase = true)) continue

                    // Check if response indicates LG webOS TV
                    val isLg = response.contains("lge.com", ignoreCase = true) ||
                            response.contains("webOS", ignoreCase = true) ||
                            response.contains("LG Electronics", ignoreCase = true) ||
                            response.contains("LG Smart TV", ignoreCase = true) ||
                            response.contains("LG", ignoreCase = true)

                    if (isLg) {
                        var tvName = "LG webOS TV"
                        var locationUrl: String? = null

                        val lines = response.lines()
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("DLNADeviceName.lge.com:", ignoreCase = true)) {
                                val name = trimmed.substringAfter(":").trim()
                                if (name.isNotEmpty()) tvName = name
                            } else if (trimmed.startsWith("LOCATION:", ignoreCase = true)) {
                                locationUrl = trimmed.substringAfter(":").trim()
                            } else if (trimmed.startsWith("Server:", ignoreCase = true) && tvName == "LG webOS TV") {
                                val s = trimmed.substringAfter(":").trim()
                                if (s.isNotEmpty() && !s.equals("webos", ignoreCase = true)) {
                                    tvName = "LG TV ($s)"
                                }
                            }
                        }

                        // Emit device with current name
                        emitDevice(
                            TvDevice(
                                id = "lg_$ip",
                                name = tvName,
                                ip = ip,
                                brand = TvBrand.LG_WEBOS,
                                port = 3001
                            )
                        )

                        // If name is still generic, fetch friendly name from LOCATION XML
                        if (locationUrl != null && tvName == "LG webOS TV") {
                            launch {
                                val resolvedName = fetchFriendlyNameFromXml(locationUrl)
                                if (resolvedName != null && resolvedName.isNotEmpty()) {
                                    emitDevice(
                                        TvDevice(
                                            id = "lg_$ip",
                                            name = resolvedName,
                                            ip = ip,
                                            brand = TvBrand.LG_WEBOS,
                                            port = 3001
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "SSDP receive loop: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP scan exception", e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun fetchFriendlyNameFromXml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            if (body.contains("<friendlyName>", ignoreCase = true)) {
                return@withContext body.substringAfter("<friendlyName>").substringBefore("</friendlyName>").trim()
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Android NsdManager service discovery for Android TV Remote v2.
     */
    private fun runNsdDiscovery() {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service found: ${serviceInfo.serviceName}")
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Nsd startDiscovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(
                "_androidtvremote2._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                nsdDiscoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Nsd discoverServices failed", e)
        }
    }

    /**
     * Direct mDNS Multicast Query (RFC 6762):
     * Sends a direct PTR query for "_androidtvremote2._tcp.local" to 224.0.0.251:5353.
     * Serves as an additional robust discovery layer when Android NsdManager is disabled or buggy.
     */
    private suspend fun runDirectMdnsQuery() = withContext(Dispatchers.IO) {
        var mSocket: MulticastSocket? = null
        try {
            mSocket = try {
                MulticastSocket(5353).apply {
                    reuseAddress = true
                }
            } catch (_: Exception) {
                MulticastSocket().apply {
                    reuseAddress = true
                }
            }
            mSocket.soTimeout = 2500
            try {
                mSocket.joinGroup(InetAddress.getByName("224.0.0.251"))
            } catch (_: Exception) {}

            val queryPacket = buildMdnsPtrQuery("_androidtvremote2._tcp.local")

            launch {
                val group = InetAddress.getByName("224.0.0.251")
                for (i in 0 until 3) {
                    if (!isActive) break
                    try {
                        val packet = DatagramPacket(queryPacket, queryPacket.size, group, 5353)
                        mSocket.send(packet)
                    } catch (_: Exception) {}
                    delay(800)
                }
            }

            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            val start = System.currentTimeMillis()

            while (isActive && (System.currentTimeMillis() - start) < 10000) {
                try {
                    packet.length = buf.size
                    mSocket.receive(packet)
                    val rawIp = packet.address.hostAddress ?: continue
                    val ip = rawIp.substringBefore("%")
                    if (ip.startsWith("fe80", ignoreCase = true)) continue
                    val data = packet.data.copyOf(packet.length)
                    val content = String(data, 0, packet.length, Charsets.ISO_8859_1)

                    if (content.contains("androidtvremote2", ignoreCase = true)) {
                        val tvName = extractMdnsInstanceName(data) ?: "Android TV"
                        emitDevice(
                            TvDevice(
                                id = "atv_$ip",
                                name = tvName,
                                ip = ip,
                                brand = TvBrand.ANDROID_TV,
                                port = 6466
                            )
                        )
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            try {
                mSocket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun buildMdnsPtrQuery(service: String): ByteArray {
        val bos = ByteArrayOutputStream()
        // Transaction ID = 0, Flags = 0 (Standard Query)
        bos.write(byteArrayOf(0, 0, 0, 0))
        // Questions = 1, Answer RRs = 0, Authority RRs = 0, Additional RRs = 0
        bos.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0))

        // QNAME
        for (part in service.split(".")) {
            if (part.isEmpty()) continue
            val b = part.toByteArray(Charsets.UTF_8)
            bos.write(b.size)
            bos.write(b)
        }
        bos.write(0) // Null root label

        // QTYPE = PTR (12), QCLASS = IN (1)
        bos.write(byteArrayOf(0, 12, 0, 1))
        return bos.toByteArray()
    }

    private fun extractMdnsInstanceName(data: ByteArray): String? {
        val str = String(data, Charsets.UTF_8)
        val pattern = Regex("([a-zA-Z0-9 _-]{3,32})\\._androidtvremote2")
        val match = pattern.find(str)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (ia in iface.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null) list.add(bcast)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun normalizeName(name: String): String {
        return name
            .lowercase()
            .replace("_androidtvremote2", "")
            .replace("._tcp", "")
            .replace(".local", "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun emitDevice(device: TvDevice) {
        // Drop unusable link-local IPv6 addresses
        if (device.ip.startsWith("fe80", ignoreCase = true) || device.ip.contains("%")) {
            return
        }

        val isNewIpv4 = !device.ip.contains(":")
        val newNormName = normalizeName(device.name)

        synchronized(seenDevices) {
            // Check if device with exact same IP is already seen
            val existingByIp = seenDevices.values.firstOrNull { it.ip.equals(device.ip, ignoreCase = true) }
            if (existingByIp != null) {
                val betterName = if ((existingByIp.name == "Android TV" || existingByIp.name == "LG webOS TV") &&
                    device.name != "Android TV" && device.name != "LG webOS TV") {
                    device.name
                } else {
                    existingByIp.name
                }
                seenDevices[existingByIp.id] = device.copy(id = existingByIp.id, name = betterName)
                _discoveredDevices.value = seenDevices.values.toList()
                return
            }

            // Check if device with same brand and normalized name is already seen (e.g. IPv4 vs IPv6 duplicate)
            val existingByName = seenDevices.values.firstOrNull { existing ->
                if (existing.brand != device.brand) return@firstOrNull false
                val existNorm = normalizeName(existing.name)
                if (existNorm.isEmpty() || newNormName.isEmpty()) return@firstOrNull false
                existNorm == newNormName ||
                        (existNorm != "androidtv" && existNorm != "lgwebostv" &&
                                (existNorm.contains(newNormName) || newNormName.contains(existNorm)))
            }

            if (existingByName != null) {
                val existingIsIpv4 = !existingByName.ip.contains(":")
                if (isNewIpv4 && !existingIsIpv4) {
                    // Upgrade: Remove IPv6 duplicate and replace with IPv4 entry
                    seenDevices.remove(existingByName.id)
                    seenDevices[device.id] = device
                } else if (!isNewIpv4 && existingIsIpv4) {
                    // Existing entry is already IPv4; ignore IPv6 duplicate
                    return
                } else {
                    // Both same IP family: if new has better name, update
                    if ((existingByName.name == "Android TV" || existingByName.name == "LG webOS TV") &&
                        device.name != "Android TV" && device.name != "LG webOS TV") {
                        seenDevices[existingByName.id] = existingByName.copy(name = device.name)
                    }
                    return
                }
            } else {
                seenDevices[device.id] = device
            }

            _discoveredDevices.value = seenDevices.values.toList()
        }
    }

    /**
     * Manual IP probe for fallback connection.
     * Probes ports 6466 (Android TV Remote v2) and 3001/3000 (LG webOS).
     */
    suspend fun probeManualIp(rawInput: String): TvDevice? = withContext(Dispatchers.IO) {
        var clean = rawInput.trim()
        if (clean.startsWith("http://", ignoreCase = true)) clean = clean.substring(7)
        if (clean.startsWith("https://", ignoreCase = true)) clean = clean.substring(8)
        if (clean.startsWith("ws://", ignoreCase = true)) clean = clean.substring(5)
        if (clean.startsWith("wss://", ignoreCase = true)) clean = clean.substring(6)
        clean = clean.substringBefore(":").substringBefore("/").trim()

        if (clean.isEmpty()) return@withContext null

        if (probePort(clean, 6466, 1500)) {
            val dev = TvDevice(
                id = "atv_$clean",
                name = "Android TV ($clean)",
                ip = clean,
                brand = TvBrand.ANDROID_TV,
                port = 6466
            )
            emitDevice(dev)
            return@withContext dev
        }

        if (probePort(clean, 3001, 1500)) {
            val dev = TvDevice(
                id = "lg_$clean",
                name = "LG webOS TV ($clean)",
                ip = clean,
                brand = TvBrand.LG_WEBOS,
                port = 3001
            )
            emitDevice(dev)
            return@withContext dev
        }

        if (probePort(clean, 3000, 1500)) {
            val dev = TvDevice(
                id = "lg_$clean",
                name = "LG webOS TV ($clean)",
                ip = clean,
                brand = TvBrand.LG_WEBOS,
                port = 3000
            )
            emitDevice(dev)
            return@withContext dev
        }

        null
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "TvDiscoveryManager"
    }
}
