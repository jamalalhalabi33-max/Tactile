package com.example.model

data class KeyboardSession(
    val isActive: Boolean = false,
    val initialText: String = "",
    val label: String = "TV Text Input",
    val isPassword: Boolean = false
)
