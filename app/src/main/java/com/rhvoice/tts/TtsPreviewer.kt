package com.rhvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsPreviewer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
        } else null

    private var holdingFocus = false

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(audioAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = releaseFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = releaseFocus()
                override fun onError(utteranceId: String?, errorCode: Int) = releaseFocus()
            })
        }
    }

    fun speak(
        text: String,
        voiceName: String?,
        localeTag: String?,
        pitch: Float,
        rate: Float,
        volume: Float,
        pan: Float,
        duckMusic: Boolean,
    ) {
        if (!ready) return

        if (!localeTag.isNullOrBlank()) {
            tts.language = Locale.forLanguageTag(localeTag)
        }
        if (!voiceName.isNullOrBlank()) {
            tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
        }
        tts.setPitch(pitch.coerceIn(0.1f, 2.0f))
        tts.setSpeechRate(rate.coerceIn(0.1f, 3.0f))

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.coerceIn(-1f, 1f))
        }

        // QUEUE_FLUSH means a previous preview's onDone won't fire — release any focus we held.
        releaseFocus()
        if (duckMusic) requestFocus()
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "rhvoice-preview")
        if (res != TextToSpeech.SUCCESS) releaseFocus()
    }

    private fun requestFocus() {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        holdingFocus = ok == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun availableVoices(): List<android.speech.tts.Voice> =
        if (ready) tts.voices?.toList().orEmpty() else emptyList()

    fun availableLanguages(): List<Locale> =
        if (ready) tts.availableLanguages?.toList().orEmpty() else emptyList()

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        releaseFocus()
    }
}
