package com.example.remote

import android.content.Context
import android.util.Log
import com.example.model.ConnectionState
import com.example.model.Key
import com.example.model.KeyboardSession
import com.example.model.TvBrand
import com.example.model.TvDevice
import com.example.storage.TvStorage
import com.example.tv.AndroidTvController
import com.example.tv.LgWebOsController
import com.example.tv.TvController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class RemoteManager(
    private val context: Context,
    val storage: TvStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentDevice = MutableStateFlow<TvDevice?>(null)
    val currentDevice: StateFlow<TvDevice?> = _currentDevice.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _keyboardSession = MutableStateFlow(KeyboardSession())
    val keyboardSession: StateFlow<KeyboardSession> = _keyboardSession.asStateFlow()

    private var activeController: TvController? = null
    private var controllerStateJob: Job? = null
    private var keyboardJob: Job? = null
    private var autoReconnectJob: Job? = null

    // Dedicated key queue on tap path
    private val keyChannel = Channel<Key>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Offline buffer: max 3 events, TTL 1.5 seconds (1500 ms)
    private data class OfflineKey(val key: Key, val timestamp: Long)
    private val offlineQueue = ArrayDeque<OfflineKey>(3)
    private val offlineLock = Any()

    init {
        // Start processing keys on dedicated IO thread immediately
        scope.launch(Dispatchers.IO) {
            for (key in keyChannel) {
                processKey(key)
            }
        }

        // Initialize last-used device without blocking the first frame
        scope.launch(Dispatchers.IO) {
            val lastDevice = storage.getLastUsedDevice()
            if (lastDevice != null) {
                selectDevice(lastDevice)
            }
        }
    }

    fun sendKey(key: Key): Boolean {
        return keyChannel.trySend(key).isSuccess
    }

    fun sendText(text: String) {
        activeController?.sendText(text)
    }

    fun sendBackspace(count: Int = 1) {
        activeController?.sendBackspace(count)
    }

    fun sendEnter() {
        activeController?.sendEnter()
    }

    fun setKeyboardActive(active: Boolean) {
        _keyboardSession.value = _keyboardSession.value.copy(isActive = active)
        activeController?.setKeyboardActive(active)
    }

    private fun processKey(key: Key) {
        val controller = activeController
        val state = _connectionState.value

        if (controller != null && state is ConnectionState.Connected) {
            controller.send(key)
        } else {
            synchronized(offlineLock) {
                val now = System.currentTimeMillis()
                if (offlineQueue.size >= 3) {
                    offlineQueue.pollFirst()
                }
                offlineQueue.offerLast(OfflineKey(key, now))
            }
        }
    }

    fun selectDevice(device: TvDevice) {
        if (_currentDevice.value?.id == device.id && activeController != null) {
            return
        }

        activeController?.disconnect()
        controllerStateJob?.cancel()
        keyboardJob?.cancel()
        autoReconnectJob?.cancel()
        _keyboardSession.value = KeyboardSession(isActive = false)

        _currentDevice.value = device
        scope.launch {
            storage.saveDevice(device)
        }

        val controller: TvController = when (device.brand) {
            TvBrand.LG_WEBOS -> LgWebOsController(
                device = device,
                onClientKeyUpdated = { newKey ->
                    scope.launch {
                        storage.updateClientKey(device.id, newKey)
                    }
                }
            )
            TvBrand.ANDROID_TV -> AndroidTvController(
                device = device,
                storage = storage
            )
        }

        activeController = controller

        controllerStateJob = scope.launch {
            controller.connectionState.collect { state ->
                val prev = _connectionState.value
                _connectionState.value = state

                if (state is ConnectionState.Connected) {
                    autoReconnectJob?.cancel()
                    if (prev !is ConnectionState.Connected) {
                        flushOfflineQueue()
                    }
                } else if (state is ConnectionState.Disconnected) {
                    _keyboardSession.value = KeyboardSession(isActive = false)
                    startAutoReconnect()
                }
            }
        }

        keyboardJob = scope.launch {
            controller.keyboardSession.collect { session ->
                _keyboardSession.value = session
            }
        }

        scope.launch {
            controller.connect()
        }
    }

    private fun flushOfflineQueue() {
        val toSend = mutableListOf<Key>()
        synchronized(offlineLock) {
            val now = System.currentTimeMillis()
            while (offlineQueue.isNotEmpty()) {
                val item = offlineQueue.pollFirst() ?: break
                if (now - item.timestamp <= 1500L) {
                    toSend.add(item.key)
                }
            }
        }

        for (k in toSend) {
            activeController?.send(k)
        }
    }

    private fun startAutoReconnect() {
        if (_currentDevice.value == null) return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            var backoffMs = 250L
            val maxBackoff = 4000L

            while (isActive && _connectionState.value is ConnectionState.Disconnected) {
                delay(backoffMs)
                if (!isActive) break

                Log.d(TAG, "Attempting auto-reconnect with backoff $backoffMs ms")
                activeController?.connect()

                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoff)
            }
        }
    }

    fun submitPairingCode(code: String) {
        activeController?.submitPairingCode(code)
    }

    fun onResume() {
        val state = _connectionState.value
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            activeController?.let {
                scope.launch { it.connect() }
            }
        }
    }

    fun disconnect() {
        autoReconnectJob?.cancel()
        controllerStateJob?.cancel()
        keyboardJob?.cancel()
        activeController?.disconnect()
        activeController = null
        _connectionState.value = ConnectionState.Disconnected
        _keyboardSession.value = KeyboardSession(isActive = false)
    }

    companion object {
        private const val TAG = "RemoteManager"
    }
}
