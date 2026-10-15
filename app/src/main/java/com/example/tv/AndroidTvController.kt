package com.example.tv

import android.util.Log
import com.example.model.ConnectionState
import com.example.model.Key
import com.example.model.KeyboardSession
import com.example.model.TvBrand
import com.example.model.TvDevice
import com.example.storage.TvStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class AndroidTvController(
    private val device: TvDevice,
    private val storage: TvStorage
) : TvController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _keyboardSession = MutableStateFlow(KeyboardSession())
    override val keyboardSession: StateFlow<KeyboardSession> = _keyboardSession.asStateFlow()

    private val imeCounter = AtomicInteger(1)
    private val fieldCounter = AtomicInteger(1)

    private var commandSocket: SSLSocket? = null
    private var commandOut: OutputStream? = null
    private var commandIn: InputStream? = null
    private var readJob: Job? = null

    // For pairing channel
    private var pairingSocket: SSLSocket? = null
    private var pairingOut: OutputStream? = null
    private var pairingIn: InputStream? = null
    private var clientCert: X509Certificate? = null
    private var serverCert: X509Certificate? = null
    private var sslContext: SSLContext? = null

    override suspend fun connect() {
        if (_connectionState.value is ConnectionState.Connecting || _connectionState.value is ConnectionState.Connected) {
            return
        }
        _connectionState.value = ConnectionState.Connecting

        try {
            val (sslCtx, cert) = AndroidTvCertificateHelper.getOrCreateSslContext(storage)
            this.sslContext = sslCtx
            this.clientCert = cert

            // Try connecting to command channel port 6466 first
            tryConnectCommandChannel(sslCtx)
        } catch (e: Exception) {
            Log.w(TAG, "Command port connect error, initiating pairing: ${e.message}")
            startPairing()
        }
    }

    private fun tryConnectCommandChannel(sslCtx: SSLContext) {
        val socket = sslCtx.socketFactory.createSocket() as SSLSocket
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(device.ip, 6466), 4000)
        socket.startHandshake()

        commandSocket = socket
        commandOut = socket.outputStream
        commandIn = socket.inputStream

        // Send RemoteConfigure
        val configMsg = ProtobufWire.encodeRemoteConfigure()
        ProtobufWire.writeFramedMessage(commandOut!!, configMsg)

        _connectionState.value = ConnectionState.Connected
        startReadLoop()
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val input = commandIn ?: return@launch
            val out = commandOut ?: return@launch

            try {
                while (isActive) {
                    val frame = ProtobufWire.readFramedMessage(input)
                    handleCommandFrame(frame, out)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Command stream closed: ${e.message}")
                    _connectionState.value = ConnectionState.Disconnected
                    _keyboardSession.value = KeyboardSession(isActive = false)
                }
            }
        }
    }

    private fun handleCommandFrame(frame: ByteArray, out: OutputStream) {
        var i = 0
        while (i < frame.size) {
            val tag = frame[i].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            i++

            if (wireType == 0) { // Varint
                var v = 0L
                var shift = 0
                while (i < frame.size) {
                    val b = frame[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
            } else if (wireType == 2) { // Length-delimited
                var len = 0
                var shift = 0
                while (i < frame.size) {
                    val b = frame[i++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }

                if (fieldNum == 8) { // remote_ping_request
                    var val1 = 1
                    if (len >= 2 && (frame[i].toInt() and 0x07) == 0) {
                        val1 = frame[i + 1].toInt()
                    }
                    val resp = ProtobufWire.encodeRemotePingResponse(val1)
                    synchronized(out) {
                        ProtobufWire.writeFramedMessage(out, resp)
                    }
                } else if (fieldNum == 21) { // remote_ime_batch_edit
                    val data = frame.copyOfRange(i, i + len)
                    parseImeBatchEdit(data)
                } else if (fieldNum == 22) { // remote_ime_show_request
                    val imeData = frame.copyOfRange(i, i + len)
                    parseImeShowRequest(imeData)
                }
                i += len
            } else {
                break
            }
        }
    }

    private fun parseImeBatchEdit(data: ByteArray) {
        var idx = 0
        while (idx < data.size) {
            val tag = data[idx++].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) {
                var v = 0L
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (fieldNum == 1) imeCounter.set(v.toInt())
                if (fieldNum == 2) fieldCounter.set(v.toInt())
            } else if (wireType == 2) {
                var l = 0
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    l = l or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                idx += l
            } else {
                break
            }
        }
    }

    private fun parseImeShowRequest(data: ByteArray) {
        var text = ""
        var label = "Android TV Keyboard"
        var idx = 0
        while (idx < data.size) {
            val tag = data[idx++].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 2) {
                var len = 0
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (fieldNum == 2) { // remote_text_field_status
                    val statusBytes = data.copyOfRange(idx, idx + len)
                    parseTextFieldStatus(statusBytes)
                    return
                }
                idx += len
            } else if (wireType == 0) {
                while (idx < data.size && (data[idx++].toInt() and 0x80) != 0) {}
            } else {
                break
            }
        }
        _keyboardSession.value = KeyboardSession(
            isActive = true,
            initialText = text,
            label = label
        )
    }

    private fun parseTextFieldStatus(data: ByteArray) {
        var text = ""
        var label = "Android TV Keyboard"
        var idx = 0
        while (idx < data.size) {
            val tag = data[idx++].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) {
                var v = 0L
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (fieldNum == 1) { // counter_field
                    fieldCounter.set(v.toInt())
                }
            } else if (wireType == 2) {
                var len = 0
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (fieldNum == 2 && idx + len <= data.size) { // value
                    text = String(data, idx, len, Charsets.UTF_8)
                } else if (fieldNum == 6 && idx + len <= data.size) { // label
                    label = String(data, idx, len, Charsets.UTF_8).ifEmpty { "Android TV Keyboard" }
                }
                idx += len
            } else {
                break
            }
        }

        _keyboardSession.value = KeyboardSession(
            isActive = true,
            initialText = text,
            label = label
        )
    }

    private fun extractStringFromProto(data: ByteArray): String {
        var idx = 0
        while (idx < data.size) {
            val tag = data[idx].toInt() and 0xFF
            val wireType = tag and 0x07
            idx++
            if (wireType == 2) { // string or message
                var len = 0
                var shift = 0
                while (idx < data.size) {
                    val b = data[idx++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (len in 1..256 && idx + len <= data.size) {
                    val candidate = String(data, idx, len, Charsets.UTF_8)
                    // If candidate contains readable chars, return it
                    if (candidate.all { !it.isISOControl() }) {
                        return candidate
                    }
                }
                idx += len
            } else if (wireType == 0) {
                while (idx < data.size && (data[idx++].toInt() and 0x80) != 0) {}
            } else {
                break
            }
        }
        return ""
    }

    private fun startPairing() {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.PairingRequired(
                    prompt = "Enter 6-character code shown on TV",
                    brand = TvBrand.ANDROID_TV
                )

                val sslCtx = sslContext ?: AndroidTvCertificateHelper.getOrCreateSslContext(storage).first.also {
                    sslContext = it
                }

                val socket = sslCtx.socketFactory.createSocket() as SSLSocket
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(device.ip, 6467), 5000)
                socket.startHandshake()

                val peerCerts = socket.session.peerCertificates
                if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                    serverCert = peerCerts[0] as X509Certificate
                }

                pairingSocket = socket
                pairingOut = socket.outputStream
                pairingIn = socket.inputStream

                // 1. Send PairingRequest
                ProtobufWire.writeFramedMessage(pairingOut!!, ProtobufWire.encodePairingRequest())
                val ack1 = ProtobufWire.readFramedMessage(pairingIn!!)

                // 2. Send PairingOption
                ProtobufWire.writeFramedMessage(pairingOut!!, ProtobufWire.encodePairingOption())
                val ack2 = ProtobufWire.readFramedMessage(pairingIn!!)

                // 3. Send PairingConfiguration
                ProtobufWire.writeFramedMessage(pairingOut!!, ProtobufWire.encodePairingConfiguration())
                val ack3 = ProtobufWire.readFramedMessage(pairingIn!!)

                Log.d(TAG, "Pairing handshake initiated. TV is now displaying 6-char code.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start pairing", e)
                _connectionState.value = ConnectionState.Error("Pairing failed: ${e.message}")
            }
        }
    }

    override fun submitPairingCode(code: String) {
        scope.launch {
            val out = pairingOut
            val input = pairingIn
            if (out == null || input == null) {
                _connectionState.value = ConnectionState.Error("Pairing socket is not ready. Try scanning again.")
                return@launch
            }

            try {
                val cleanCode = code.trim().uppercase()
                val codeBytes = if (cleanCode.length == 6 && cleanCode.matches(Regex("[0-9A-F]{6}"))) {
                    cleanCode.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else {
                    cleanCode.toByteArray(Charsets.UTF_8)
                }

                val digest = MessageDigest.getInstance("SHA-256")
                clientCert?.encoded?.let { digest.update(it) }
                serverCert?.encoded?.let { digest.update(it) }
                digest.update(codeBytes)
                val secretHash = digest.digest()

                val secretMsg = ProtobufWire.encodePairingSecret(secretHash)
                ProtobufWire.writeFramedMessage(out, secretMsg)

                val response = ProtobufWire.readFramedMessage(input)
                Log.d(TAG, "Pairing secret response received: size=${response.size}")

                try {
                    pairingSocket?.close()
                } catch (_: Exception) {}
                pairingSocket = null

                _connectionState.value = ConnectionState.Connecting
                val sslCtx = sslContext ?: AndroidTvCertificateHelper.getOrCreateSslContext(storage).first
                tryConnectCommandChannel(sslCtx)
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting pairing code", e)
                _connectionState.value = ConnectionState.Error("Wrong pairing code or timeout: ${e.message}")
            }
        }
    }

    override fun send(key: Key) {
        val out = commandOut ?: return
        val keyCode = when (key) {
            Key.UP -> 19       // KEYCODE_DPAD_UP
            Key.DOWN -> 20     // KEYCODE_DPAD_DOWN
            Key.LEFT -> 21     // KEYCODE_DPAD_LEFT
            Key.RIGHT -> 22    // KEYCODE_DPAD_RIGHT
            Key.OK -> 23       // KEYCODE_DPAD_CENTER
            Key.BACK -> 4      // KEYCODE_BACK
            Key.VOLUME_UP -> 24 // KEYCODE_VOLUME_UP
            Key.VOLUME_DOWN -> 25 // KEYCODE_VOLUME_DOWN
            Key.MUTE -> 164    // KEYCODE_VOLUME_MUTE
        }

        val msg = ProtobufWire.encodeRemoteKeyInject(keyCode, direction = 3)
        synchronized(out) {
            try {
                ProtobufWire.writeFramedMessage(out, msg)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending key to Android TV: ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    override fun sendText(text: String) {
        val out = commandOut ?: return
        val batchEdit = ProtobufWire.encodeRemoteImeBatchEdit(
            text = text,
            imeCounter = imeCounter.getAndIncrement(),
            fieldCounter = fieldCounter.get()
        )
        synchronized(out) {
            try {
                ProtobufWire.writeFramedMessage(out, batchEdit)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending IME text: ${e.message}")
            }
        }
    }

    override fun sendBackspace(count: Int) {
        val out = commandOut ?: return
        if (count <= 0) return
        // KEYCODE_DEL = 67
        synchronized(out) {
            try {
                repeat(count) {
                    val msg = ProtobufWire.encodeRemoteKeyInject(keyCode = 67, direction = 3)
                    ProtobufWire.writeFramedMessage(out, msg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending backspace: ${e.message}")
            }
        }
    }

    override fun sendEnter() {
        val out = commandOut ?: return
        // KEYCODE_ENTER = 66
        val msg = ProtobufWire.encodeRemoteKeyInject(keyCode = 66, direction = 3)
        synchronized(out) {
            try {
                ProtobufWire.writeFramedMessage(out, msg)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending enter: ${e.message}")
            }
        }
    }

    override fun setKeyboardActive(active: Boolean) {
        _keyboardSession.value = _keyboardSession.value.copy(isActive = active)
    }

    override fun disconnect() {
        readJob?.cancel()
        try {
            commandSocket?.close()
            pairingSocket?.close()
        } catch (_: Exception) {}
        commandSocket = null
        pairingSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _keyboardSession.value = KeyboardSession(isActive = false)
    }

    companion object {
        private const val TAG = "AndroidTvController"
    }
}
