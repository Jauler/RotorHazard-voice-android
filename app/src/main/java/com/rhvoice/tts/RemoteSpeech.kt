package com.rhvoice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "RemoteSpeech"
private const val CONNECT_TIMEOUT_MS = 2_000
private const val READ_TIMEOUT_MS = 8_000

/**
 * Announcements spoken by an OpenAI-compatible speech server (`POST /v1/audio/speech`,
 * e.g. piper-speech) instead of the device's TTS engine. Blocking: call off the main thread.
 */
class RemoteSpeech(
    private val attrs: AudioAttributes,
    private val boosted: BoostedAudioSession,
) {

    class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray)

    /**
     * Fetch [text] from the server at [baseUrl] and play it to the end, not starting before
     * [notBeforeMs] ([SystemClock.uptimeMillis]). Returns false if the server could not be used,
     * so the caller can fall back to the device voice.
     */
    fun speak(baseUrl: String, text: String, rate: Float, volume: Float, notBeforeMs: Long = 0L): Boolean {
        val wav = fetch(baseUrl, text, rate) ?: return false
        val pcm = parseWav(wav) ?: run {
            Log.w(TAG, "Server returned audio that is not 16-bit PCM WAV")
            return false
        }
        val wait = notBeforeMs - SystemClock.uptimeMillis()
        if (wait > 0) Thread.sleep(wait)
        play(pcm, volume)
        return true
    }

    private fun fetch(baseUrl: String, text: String, rate: Float): ByteArray? {
        val started = SystemClock.uptimeMillis()
        val conn = try {
            URL(baseUrl.trim().trimEnd('/') + "/v1/audio/speech").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "Invalid speech server URL '$baseUrl'", e)
            return null
        }
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("input", text)
                .put("response_format", "wav")
                .put("speed", rate.coerceIn(0.25f, 4.0f).toDouble())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Speech server answered ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { it.readBytes() }.also {
                Log.i(TAG, "Fetched ${it.size} bytes in ${SystemClock.uptimeMillis() - started} ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speech server request failed after ${SystemClock.uptimeMillis() - started} ms: $e")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun play(pcm: Pcm, volume: Float) {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.sampleRate)
                        .setChannelMask(
                            if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(pcm.data.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(boosted.sessionId)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack build failed", e)
            return
        }
        try {
            val written = track.write(pcm.data, 0, pcm.data.size)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written")
                return
            }
            track.setVolume(volume)
            track.play()
            val frames = pcm.data.size / (2 * pcm.channels)
            Thread.sleep(frames * 1000L / pcm.sampleRate + 100L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Playback failed", e)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    companion object {
        /** 16-bit PCM WAV to raw samples; null for anything else. */
        fun parseWav(bytes: ByteArray): Pcm? {
            if (bytes.size < 12) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null
            var sampleRate = 0
            var channels = 0
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = buf.getInt(pos + 4)
                val start = pos + 8
                when (id) {
                    "fmt " -> {
                        if (size < 16 || start + 16 > bytes.size) return null
                        if (buf.getShort(start).toInt() != 1 || buf.getShort(start + 14).toInt() != 16) return null
                        channels = buf.getShort(start + 2).toInt()
                        sampleRate = buf.getInt(start + 4)
                    }
                    "data" -> {
                        if (sampleRate <= 0 || channels !in 1..2) return null
                        // streaming writers put 0 or 0xFFFFFFFF here; take what is there
                        val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
                        return Pcm(sampleRate, channels, bytes.copyOfRange(start, end))
                    }
                }
                if (size < 0) return null
                pos = start + size + (size and 1)
            }
            return null
        }
    }
}
