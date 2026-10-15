package com.example.tv

import com.example.model.ConnectionState
import com.example.model.Key
import com.example.model.KeyboardSession
import kotlinx.coroutines.flow.StateFlow

interface TvController {
    val connectionState: StateFlow<ConnectionState>
    val keyboardSession: StateFlow<KeyboardSession>
    suspend fun connect()
    fun send(key: Key)
    fun sendText(text: String)
    fun sendBackspace(count: Int = 1)
    fun sendEnter()
    fun setKeyboardActive(active: Boolean)
    fun disconnect()
    fun submitPairingCode(code: String) {}
}
