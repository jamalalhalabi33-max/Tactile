package com.example.tv

import android.util.Log
import com.example.model.ConnectionState
import com.example.model.Key
import com.example.model.KeyboardSession
import com.example.model.TvBrand
import com.example.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LgWebOsController(
    private val device: TvDevice,
    private val onClientKeyUpdated: (String) -> Unit
) : TvController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _keyboardSession = MutableStateFlow(KeyboardSession())
    override val keyboardSession: StateFlow<KeyboardSession> = _keyboardSession.asStateFlow()

    private val requestId = AtomicInteger(1)
    private var commandSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentClientKey: String? = device.clientKey
    private var isMuted = false

    private val httpClient: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> hostname == device.ip }
            .pingInterval(5, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun connect() {
        if (_connectionState.value is ConnectionState.Connecting || _connectionState.value is ConnectionState.Connected) {
            return
        }
        _connectionState.value = ConnectionState.Connecting

        // Primary: wss://ip:3001, fallback to ws://ip:3000 if 3001 is unavailable
        val primaryUrl = "wss://${device.ip}:3001/"
        val request = Request.Builder().url(primaryUrl).build()

        commandSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendRegistration(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommandMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Secure port 3001 failed: ${t.message}. Trying port 3000 fallback.")
                tryFallbackPort3000()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                _keyboardSession.value = KeyboardSession(isActive = false)
            }
        })
    }

    private fun tryFallbackPort3000() {
        val fallbackUrl = "ws://${device.ip}:3000/"
        val fallbackRequest = Request.Builder().url(fallbackUrl).build()

        commandSocket = httpClient.newWebSocket(fallbackRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendRegistration(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommandMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error("Could not connect to LG TV: ${t.message ?: "Unreachable"}")
                _keyboardSession.value = KeyboardSession(isActive = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                _keyboardSession.value = KeyboardSession(isActive = false)
            }
        })
    }

    private fun sendRegistration(webSocket: WebSocket) {
        val regMsg = JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            val payload = JSONObject().apply {
                put("forcePairing", false)
                put("pairingType", "PROMPT")
                if (!currentClientKey.isNullOrEmpty()) {
                    put("client-key", currentClientKey)
                }
                val manifest = JSONObject().apply {
                    put("manifestVersion", 1)
                    put("appVersion", "1.0")
                    val signed = JSONObject().apply {
                        put("created", "20140509")
                        put("appId", "com.tactile.remote")
                        put("vendorId", "com.tactile")
                        put("localizedAppNames", JSONObject().put("", "Tactile"))
                        put("permissions", org.json.JSONArray().apply {
                            put("CONTROL_INPUT_TEXT")
                            put("CONTROL_MOUSE_AND_KEYBOARD")
                            put("READ_INSTALLED_APPS")
                            put("READ_RUNNING_APPS")
                            put("CONTROL_AUDIO")
                            put("CONTROL_POWER")
                        })
                        put("serial", "tactile-client")
                    }
                    put("signed", signed)
                }
                put("manifest", manifest)
            }
            put("payload", payload)
        }
        webSocket.send(regMsg.toString())
    }

    private fun handleCommandMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val id = json.optString("id")

            when (type) {
                "response" -> {
                    val payload = json.optJSONObject("payload")
                    val pairingType = payload?.optString("pairingType")
                    if (pairingType == "PROMPT") {
                        _connectionState.value = ConnectionState.PairingRequired(
                            prompt = "Accept the prompt on your LG TV",
                            brand = TvBrand.LG_WEBOS
                        )
                    }

                    if (id == "req_pointer" && payload != null) {
                        val socketPath = payload.optString("socketPath")
                        if (socketPath.isNotEmpty()) {
                            connectPointerSocket(socketPath)
                        }
                    }

                    // Handle IME keyboard subscription responses
                    if (id == "sub_keyboard" || id.startsWith("sub_ime")) {
                        handleImePayload(payload)
                    }
                }
                "registered" -> {
                    val payload = json.optJSONObject("payload")
                    val key = payload?.optString("client-key")
                    if (!key.isNullOrEmpty()) {
                        currentClientKey = key
                        onClientKeyUpdated(key)
                    }
                    _connectionState.value = ConnectionState.Connected
                    // Request pointer input socket immediately
                    requestPointerSocket(webSocket)
                    // Subscribe to TV keyboard IME events immediately
                    subscribeKeyboardIme(webSocket)
                }
                "error" -> {
                    val error = json.optString("error", "Unknown error")
                    if (error.contains("403") || error.contains("cancelled")) {
                        currentClientKey = null
                        _connectionState.value = ConnectionState.PairingRequired(
                            prompt = "Pairing was rejected or expired. Please accept on your TV.",
                            brand = TvBrand.LG_WEBOS
                        )
                    } else {
                        _connectionState.value = ConnectionState.Error("TV Error: $error")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing webOS message", e)
        }
    }

    private fun subscribeKeyboardIme(webSocket: WebSocket) {
        val req = JSONObject().apply {
            put("type", "subscribe")
            put("id", "sub_keyboard")
            put("uri", "ssap://com.webos.service.ime/registerRemoteKeyboard")
        }
        webSocket.send(req.toString())
    }

    private fun handleImePayload(payload: JSONObject?) {
        if (payload == null) return
        val currentWidget = payload.optJSONObject("currentWidget")
        val isFocused = when {
            currentWidget != null -> currentWidget.optBoolean("focus", false)
            payload.has("focused") -> payload.optBoolean("focused", false)
            payload.has("focus") -> payload.optBoolean("focus", false)
            else -> false
        }

        val initialText = currentWidget?.optString("value", "") ?: payload.optString("value", "")

        _keyboardSession.value = if (isFocused) {
            KeyboardSession(
                isActive = true,
                initialText = initialText,
                label = "LG TV Keyboard"
            )
        } else {
            KeyboardSession(isActive = false)
        }
    }

    private fun requestPointerSocket(webSocket: WebSocket) {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "req_pointer")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        webSocket.send(req.toString())
    }

    private fun connectPointerSocket(socketPath: String) {
        val request = Request.Builder().url(socketPath).build()
        pointerSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Pointer socket warm and connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Pointer socket failure: ${t.message}")
            }
        })
    }

    override fun send(key: Key) {
        scope.launch {
            when (key) {
                Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.OK, Key.BACK -> {
                    val btnName = when (key) {
                        Key.UP -> "UP"
                        Key.DOWN -> "DOWN"
                        Key.LEFT -> "LEFT"
                        Key.RIGHT -> "RIGHT"
                        Key.OK -> "ENTER"
                        Key.BACK -> "BACK"
                        else -> "ENTER"
                    }
                    val frame = "type:button\nname:$btnName\n\n"
                    val sent = pointerSocket?.send(frame) ?: false
                    if (!sent) {
                        // Fallback
                    }
                }
                Key.VOLUME_UP -> {
                    sendSsapRequest("ssap://audio/volumeUp")
                }
                Key.VOLUME_DOWN -> {
                    sendSsapRequest("ssap://audio/volumeDown")
                }
                Key.MUTE -> {
                    isMuted = !isMuted
                    sendSsapRequest(
                        uri = "ssap://audio/setMute",
                        payload = JSONObject().put("mute", isMuted)
                    )
                }
            }
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        scope.launch {
            sendSsapRequest(
                uri = "ssap://com.webos.service.ime/insertText",
                payload = JSONObject().apply {
                    put("text", text)
                    put("replace", 0)
                }
            )
        }
    }

    override fun sendBackspace(count: Int) {
        if (count <= 0) return
        scope.launch {
            sendSsapRequest(
                uri = "ssap://com.webos.service.ime/deleteCharacters",
                payload = JSONObject().apply {
                    put("count", count)
                }
            )
        }
    }

    override fun sendEnter() {
        scope.launch {
            sendSsapRequest(
                uri = "ssap://com.webos.service.ime/sendEnterKey",
                payload = JSONObject()
            )
        }
    }

    override fun setKeyboardActive(active: Boolean) {
        _keyboardSession.value = _keyboardSession.value.copy(isActive = active)
        if (!active) {
            sendSsapRequest(
                uri = "ssap://com.webos.service.ime/setKeyboardActive",
                payload = JSONObject().apply {
                    put("active", false)
                }
            )
        }
    }

    private fun sendSsapRequest(uri: String, payload: JSONObject? = null) {
        val id = "req_${requestId.getAndIncrement()}"
        val req = JSONObject().apply {
            put("type", "request")
            put("id", id)
            put("uri", uri)
            if (payload != null) {
                put("payload", payload)
            }
        }
        commandSocket?.send(req.toString())
    }

    override fun disconnect() {
        try {
            pointerSocket?.close(1000, "Disconnect")
            commandSocket?.close(1000, "Disconnect")
        } catch (_: Exception) {}
        _connectionState.value = ConnectionState.Disconnected
        _keyboardSession.value = KeyboardSession(isActive = false)
    }

    companion object {
        private const val TAG = "LgWebOsController"
    }
}
