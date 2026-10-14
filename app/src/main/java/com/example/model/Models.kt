package com.example.model

enum class Key {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE
}

enum class TvBrand {
    LG_WEBOS,
    ANDROID_TV
}

data class TvDevice(
    val id: String,
    val name: String,
    val ip: String,
    val brand: TvBrand,
    val port: Int = if (brand == TvBrand.LG_WEBOS) 3001 else 6466,
    val clientKey: String? = null
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class PairingRequired(val prompt: String, val brand: TvBrand) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
