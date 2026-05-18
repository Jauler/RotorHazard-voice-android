package com.rhvoice

import android.app.Application
import com.rhvoice.data.SettingsRepository

class RhVoiceApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
    }

    companion object {
        private lateinit var instance: RhVoiceApp
        fun get(): RhVoiceApp = instance
    }
}
