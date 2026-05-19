package com.rhvoice.tts

import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer

/**
 * Owns an audio session ID with a [LoudnessEnhancer] attached. The volume slider runs
 * 0..[MAX_VOLUME]; values >1 translate into positive gain (up to [MAX_BOOST_MB] millibels)
 * on the enhancer, while the audible TTS / AudioTrack volume stays clamped at 1.0.
 */
class BoostedAudioSession(audioManager: AudioManager) {

    val sessionId: Int = audioManager.generateAudioSessionId()

    private val enhancer: LoudnessEnhancer? = runCatching {
        LoudnessEnhancer(sessionId).apply { enabled = true }
    }.getOrNull()

    /**
     * Push [volume] (raw slider value) to the enhancer and return the residual 0..1 scale
     * the caller should pass to its TTS/AudioTrack volume parameter.
     */
    fun applyVolume(volume: Float): Float {
        val v = volume.coerceIn(0f, MAX_VOLUME)
        val boostMb = ((v - 1f).coerceAtLeast(0f) * MAX_BOOST_MB).toInt()
        enhancer?.runCatching { setTargetGain(boostMb) }
        return v.coerceAtMost(1f)
    }

    fun release() {
        runCatching { enhancer?.release() }
    }

    companion object {
        const val MAX_VOLUME = 2.0f
        const val MAX_BOOST_MB = 1200f // +12 dB at MAX_VOLUME
    }
}
