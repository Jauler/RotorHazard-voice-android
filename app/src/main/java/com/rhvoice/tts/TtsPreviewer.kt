package com.rhvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsPreviewer(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
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
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "rhvoice-preview")
    }

    fun availableVoices(): List<android.speech.tts.Voice> =
        if (ready) tts.voices?.toList().orEmpty() else emptyList()

    fun availableLanguages(): List<Locale> =
        if (ready) tts.availableLanguages?.toList().orEmpty() else emptyList()

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
