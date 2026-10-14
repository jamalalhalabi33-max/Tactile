package com.example.tv

import com.example.model.ConnectionState
import com.example.model.Key
import kotlinx.coroutines.flow.StateFlow

interface TvController {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect()
    fun send(key: Key)
    fun disconnect()
    fun submitPairingCode(code: String) {}
}
