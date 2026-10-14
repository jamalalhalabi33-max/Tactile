package com.example.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.model.TvBrand
import com.example.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class TvDiscoveryManager(private val context: Context) {

    private val _discoveredDevices = MutableSharedFlow<TvDevice>(replay = 20)
    val discoveredDevices: SharedFlow<TvDevice> = _discoveredDevices.asSharedFlow()

    private val seenDevices = ConcurrentHashMap<String, Boolean>()
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun startScanning(scope: CoroutineScope) {
        stopScanning()
        seenDevices.clear()

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("tactile_ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        scanJob = scope.launch(Dispatchers.IO) {
            launch { runSsdpScan() }
            launch { runMdnsScan() }
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

    private suspend fun runSsdpScan() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 3000
                broadcast = true
            }

            val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"

            val ssdpBytes = ssdpMessage.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                ssdpBytes,
                ssdpBytes.size,
                InetAddress.getByName("239.255.255.250"),
                1900
            )

            // Send 3 times, 700 ms apart because UDP is lossy
            launch {
                for (i in 0 until 3) {
                    if (!isActive) break
                    try {
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "SSDP send error: ${e.message}")
                    }
                    delay(700)
                }
            }

            val buffer = ByteArray(2048)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            while (isActive && (System.currentTimeMillis() - startTime) < 8000) {
                try {
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    val ip = receivePacket.address.hostAddress ?: continue

                    var tvName = "LG webOS TV"
                    val lines = response.lines()
                    for (line in lines) {
                        if (line.startsWith("DLNADeviceName.lge.com:", ignoreCase = true)) {
                            tvName = line.substringAfter(":").trim()
                        } else if (line.startsWith("Server:", ignoreCase = true) && tvName == "LG webOS TV") {
                            val serverPart = line.substringAfter(":").trim()
                            if (serverPart.isNotEmpty()) tvName = "LG TV ($serverPart)"
                        }
                    }

                    emitDevice(
                        TvDevice(
                            id = "lg_$ip",
                            name = tvName,
                            ip = ip,
                            brand = TvBrand.LG_WEBOS,
                            port = 3001
                        )
                    )
                } catch (_: java.net.SocketTimeoutException) {
                    // Loop until scan ends
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "SSDP receive error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP scan failed", e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun runMdnsScan() {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service found: ${serviceInfo.serviceName}")
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            val name = resolved.serviceName.ifEmpty { "Android TV" }
                            emitDevice(
                                TvDevice(
                                    id = "atv_$host",
                                    name = name,
                                    ip = host,
                                    brand = TvBrand.ANDROID_TV,
                                    port = 6466
                                )
                            )
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Nsd resolveService error", e)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
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

    private fun emitDevice(device: TvDevice) {
        if (seenDevices.putIfAbsent(device.ip, true) == null) {
            _discoveredDevices.tryEmit(device)
        }
    }

    suspend fun probeManualIp(rawInput: String): TvDevice? = withContext(Dispatchers.IO) {
        // Clean IP: trim spaces and accidental ports
        var clean = rawInput.trim()
        if (clean.startsWith("http://", ignoreCase = true)) clean = clean.substring(7)
        if (clean.startsWith("https://", ignoreCase = true)) clean = clean.substring(8)
        if (clean.startsWith("ws://", ignoreCase = true)) clean = clean.substring(5)
        if (clean.startsWith("wss://", ignoreCase = true)) clean = clean.substring(6)
        clean = clean.substringBefore(":").substringBefore("/").trim()

        if (clean.isEmpty()) return@withContext null

        // Probe 6466 (Android TV Remote) first
        if (probePort(clean, 6466, 1200)) {
            return@withContext TvDevice(
                id = "atv_$clean",
                name = "Android TV ($clean)",
                ip = clean,
                brand = TvBrand.ANDROID_TV,
                port = 6466
            )
        }

        // Probe 3001 (LG webOS secure)
        if (probePort(clean, 3001, 1200)) {
            return@withContext TvDevice(
                id = "lg_$clean",
                name = "LG webOS TV ($clean)",
                ip = clean,
                brand = TvBrand.LG_WEBOS,
                port = 3001
            )
        }

        // Probe 3000 (LG webOS fallback)
        if (probePort(clean, 3000, 1200)) {
            return@withContext TvDevice(
                id = "lg_$clean",
                name = "LG webOS TV ($clean)",
                ip = clean,
                brand = TvBrand.LG_WEBOS,
                port = 3000
            )
        }

        // Default to Android TV if reachable or return fallback
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
