package com.rhvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.rhvoice.service.RaceEvents.RaceStatusPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

private const val TAG = "Tones"
private const val SAMPLE_RATE = 44_100

class ToneScheduler(context: Context) {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
        } else null

    private val tickSamples: ShortArray = triangle(440.0, 100, fadeOutMs = 0)
    private val startEndSamples: ShortArray = triangle(880.0, 700, fadeOutMs = 250)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var current: Job? = null
    private var focusHeld = false

    /**
     * Schedule the full RotorHazard beep pattern (see `rotorhazard.js` `race.callbacks.step`
     * + `callbacks.expire`). Call-site is the moment we received `stage_ready`.
     */
    fun scheduleStaging(p: RaceStatusPayload) {
        cancel()
        val anchor = System.currentTimeMillis()
        val raceStartMs = anchor + (p.stagingGapSec * 1000).roundToLong()
        val stagingTones = p.stagingTones.coerceAtLeast(0)
        val raceTimeSec = p.raceTimeSec
        val timedRace = !p.unlimitedTime && raceTimeSec > 0.0

        Log.i(TAG, "scheduleStaging: anchor=$anchor, raceStart=$raceStartMs (+${raceStartMs - anchor}ms), tones=$stagingTones, timedRace=$timedRace, raceTimeSec=$raceTimeSec")

        // Hold audio focus across each *cluster* of tones, not per beep — Android's
        // ducking transition is too slow to take effect on a 100 ms tick if focus is
        // released immediately after. Music thus ducks for the staging burst, restores
        // for the race, and ducks again for the final-5 + end tone.
        val ducklead = 200L                       // request focus this many ms before first tone
        val tailMs = 700L + 100L                  // hold past the final tone of the cluster
        current = scope.launch {
            try {
                acquireFocus()
                for (i in 0 until stagingTones) {
                    val target = anchor + i * 1000L
                    if (target >= raceStartMs) break
                    delayUntil(target)
                    Log.i(TAG, "staging tick $i at ${System.currentTimeMillis() - anchor}ms")
                    play(tickSamples)
                }
                delayUntil(raceStartMs)
                Log.i(TAG, "start tone at ${System.currentTimeMillis() - anchor}ms")
                play(startEndSamples)
                delayUntil(raceStartMs + tailMs)
            } finally {
                releaseFocus()
            }

            if (timedRace) {
                val raceEndMs = raceStartMs + (raceTimeSec * 1000).roundToLong()
                val ticks = if (raceTimeSec >= 5.0) 5 else raceTimeSec.toInt()
                val firstFinalTick = raceEndMs - ticks * 1000L
                Log.i(TAG, "end-of-race chain: raceEnd=$raceEndMs (+${raceEndMs - anchor}ms), ticks=$ticks")
                delayUntil(firstFinalTick - ducklead)
                try {
                    acquireFocus()
                    for (i in 0 until ticks) {
                        val target = raceEndMs - (ticks - i) * 1000L
                        if (target <= System.currentTimeMillis()) continue
                        delayUntil(target)
                        Log.i(TAG, "final-5 tick $i at ${System.currentTimeMillis() - anchor}ms")
                        play(tickSamples)
                    }
                    delayUntil(raceEndMs)
                    Log.i(TAG, "end tone at ${System.currentTimeMillis() - anchor}ms")
                    play(startEndSamples)
                    delayUntil(raceEndMs + tailMs)
                } finally {
                    releaseFocus()
                }
            }
            Log.i(TAG, "schedule complete at ${System.currentTimeMillis() - anchor}ms")
        }
    }

    private suspend fun delayUntil(targetMs: Long) {
        val d = targetMs - System.currentTimeMillis()
        if (d > 0) delay(d)
    }

    fun cancel() {
        if (current != null) Log.i(TAG, "cancel()", Throwable())
        current?.cancel()
        current = null
        releaseFocus()
    }

    fun shutdown() {
        Log.i(TAG, "shutdown()")
        scope.cancel()
        releaseFocus()
    }

    // ---- audio ----

    /**
     * Build a fresh AudioTrack per play. Cheap (buffers are ~3 KB / ~62 KB), and avoids
     * the long-idle-static-track flakiness where `reloadStaticData()` silently fails after
     * a 25-second wait. Track is released on a coroutine after the tone duration.
     */
    private fun play(samples: ShortArray) {
        val track = try {
            val bytes = samples.size * 2
            val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bytes,
                    AudioTrack.MODE_STATIC,
                )
            }
            val written = t.write(samples, 0, samples.size)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written; releasing")
                t.release()
                return
            }
            t
        } catch (e: Exception) {
            Log.w(TAG, "Tone build failed", e)
            return
        }

        try {
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "Tone play() failed", e)
            runCatching { track.release() }
            return
        }
        val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 100L
        scope.launch {
            try {
                delay(durationMs)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun acquireFocus() {
        if (focusHeld) return
        focusHeld = true
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
        if (!focusHeld) return
        focusHeld = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun triangle(freqHz: Double, durationMs: Int, fadeOutMs: Int): ShortArray {
        val total = (SAMPLE_RATE.toLong() * durationMs / 1000).toInt()
        val attackSamples = (SAMPLE_RATE * 0.005).toInt() // 5 ms anti-click attack
        val fadeOutSamples = (SAMPLE_RATE.toLong() * fadeOutMs / 1000).toInt()
            .coerceAtLeast(if (fadeOutMs == 0) (SAMPLE_RATE * 0.005).toInt() else 0)
        val out = ShortArray(total)
        val period = SAMPLE_RATE / freqHz
        for (i in 0 until total) {
            val phase = ((i / period) + 0.75) % 1.0
            val raw = 4.0 * abs(phase - 0.5) - 1.0
            val attackGain = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val fadeGain = if (i >= total - fadeOutSamples)
                (total - i).toDouble() / fadeOutSamples
            else 1.0
            val v = (raw * attackGain * fadeGain * 0.7)
            out[i] = (v * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
