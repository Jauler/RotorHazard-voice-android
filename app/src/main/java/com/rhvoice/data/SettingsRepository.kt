package com.rhvoice.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        save(next)
        _settings.value = next
    }

    var batteryNudgeShown: Boolean
        get() = prefs.getBoolean(K_BATTERY_NUDGE_SHOWN, false)
        set(value) { prefs.edit().putBoolean(K_BATTERY_NUDGE_SHOWN, value).apply() }

    private fun load(): Settings = Settings(
        url = prefs.getString(K_URL, "") ?: "",
        username = prefs.getString(K_USER, "") ?: "",
        password = prefs.getString(K_PASS, "") ?: "",
        ttsVoiceName = prefs.getString(K_VOICE, null),
        ttsLocaleTag = prefs.getString(K_LOCALE, null),
        ttsPitch = prefs.getFloat(K_PITCH, 1.0f),
        ttsRate = prefs.getFloat(K_RATE, 1.0f),
        ttsVolume = prefs.getFloat(K_VOLUME, 1.0f),
        ttsPan = prefs.getFloat(K_PAN, 0.0f),
        duckMusic = prefs.getBoolean(K_DUCK, true),
    )

    private fun save(s: Settings) {
        prefs.edit()
            .putString(K_URL, s.url)
            .putString(K_USER, s.username)
            .putString(K_PASS, s.password)
            .putString(K_VOICE, s.ttsVoiceName)
            .putString(K_LOCALE, s.ttsLocaleTag)
            .putFloat(K_PITCH, s.ttsPitch)
            .putFloat(K_RATE, s.ttsRate)
            .putFloat(K_VOLUME, s.ttsVolume)
            .putFloat(K_PAN, s.ttsPan)
            .putBoolean(K_DUCK, s.duckMusic)
            .apply()
    }

    private companion object {
        const val FILE = "rhvoice-settings"
        const val K_URL = "url"
        const val K_USER = "username"
        const val K_PASS = "password"
        const val K_VOICE = "tts_voice_name"
        const val K_LOCALE = "tts_locale_tag"
        const val K_PITCH = "tts_pitch"
        const val K_RATE = "tts_rate"
        const val K_VOLUME = "tts_volume"
        const val K_PAN = "tts_pan"
        const val K_DUCK = "duck_music"
        const val K_BATTERY_NUDGE_SHOWN = "battery_nudge_shown"
    }
}
