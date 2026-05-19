package com.rhvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.rhvoice.data.SettingsRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AnnouncementSpeaker(
    context: Context,
    private val repo: SettingsRepository,
) {
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
                .setOnAudioFocusChangeListener { /* no-op: we don't need to react */ }
                .build()
        } else null

    private val outstanding = AtomicInteger(0)
    private val seq = AtomicInteger(0)

    private val sessionId: Int = audioManager.generateAudioSessionId()
    private val loudnessEnhancer: LoudnessEnhancer? = runCatching {
        LoudnessEnhancer(sessionId).apply { enabled = true }
    }.getOrNull()

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(audioAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = onUtteranceFinished()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onUtteranceFinished()
                override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()
            })
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val s = repo.settings.value

        if (!s.ttsLocaleTag.isNullOrBlank()) {
            tts.language = Locale.forLanguageTag(s.ttsLocaleTag)
        }
        if (!s.ttsVoiceName.isNullOrBlank()) {
            tts.voices?.firstOrNull { it.name == s.ttsVoiceName }?.let { tts.voice = it }
        }
        tts.setPitch(s.ttsPitch.coerceIn(0.1f, 2.0f))
        tts.setSpeechRate(s.ttsRate.coerceIn(0.1f, 3.0f))

        val v = s.ttsVolume.coerceIn(0f, MAX_VOLUME)
        val ttsVolume = v.coerceAtMost(1f)
        val boostMb = ((v - 1f).coerceAtLeast(0f) * MAX_BOOST_MB).toInt()
        loudnessEnhancer?.runCatching { setTargetGain(boostMb) }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, s.ttsPan.coerceIn(-1f, 1f))
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId)
        }

        val duck = s.duckMusic
        if (outstanding.getAndIncrement() == 0 && duck) requestFocus()
        val id = "rhvoice-${seq.incrementAndGet()}"
        val res = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        if (res != TextToSpeech.SUCCESS) onUtteranceFinished()
    }

    private fun onUtteranceFinished() {
        if (outstanding.decrementAndGet() == 0) releaseFocus()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        runCatching { loudnessEnhancer?.release() }
        releaseFocus()
    }

    companion object {
        const val MAX_VOLUME = 2.0f
        const val MAX_BOOST_MB = 1000f // +10 dB at MAX_VOLUME
    }
}
