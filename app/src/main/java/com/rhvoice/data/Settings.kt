package com.rhvoice.data

data class Settings(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val ttsVoiceName: String? = null,
    val ttsLocaleTag: String? = null,
    val ttsPitch: Float = 1.0f,
    val ttsRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val ttsPan: Float = 0.0f,
)
