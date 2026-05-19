package com.rhvoice.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.rhvoice.RhVoiceApp
import com.rhvoice.data.Settings
import com.rhvoice.data.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<Settings> = repo.settings

    fun setUrl(v: String) = repo.update { it.copy(url = v) }
    fun setUsername(v: String) = repo.update { it.copy(username = v) }
    fun setPassword(v: String) = repo.update { it.copy(password = v) }
    fun setVoice(name: String?) = repo.update { it.copy(ttsVoiceName = name) }
    fun setLocale(tag: String?) = repo.update { it.copy(ttsLocaleTag = tag) }
    fun setPitch(v: Float) = repo.update { it.copy(ttsPitch = v) }
    fun setRate(v: Float) = repo.update { it.copy(ttsRate = v) }
    fun setVolume(v: Float) = repo.update { it.copy(ttsVolume = v) }
    fun setPan(v: Float) = repo.update { it.copy(ttsPan = v) }
    fun setDuckMusic(v: Boolean) = repo.update { it.copy(duckMusic = v) }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return SettingsViewModel(RhVoiceApp.get().settingsRepository) as T
            }
        }
    }
}
