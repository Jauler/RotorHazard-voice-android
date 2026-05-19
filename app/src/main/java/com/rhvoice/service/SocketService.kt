package com.rhvoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.rhvoice.MainActivity
import com.rhvoice.RhVoiceApp
import com.rhvoice.tts.AnnouncementSpeaker
import com.rhvoice.tts.ToneScheduler
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class SocketService : Service() {

    private var socket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var speaker: AnnouncementSpeaker
    private lateinit var tones: ToneScheduler
    private var lastRaceStatus: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        speaker = AnnouncementSpeaker(this, RhVoiceApp.get().settingsRepository)
        tones = ToneScheduler(this, RhVoiceApp.get().settingsRepository)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfFully()
                return START_NOT_STICKY
            }
            else -> startConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        startInForeground("Connecting…")
        acquireWakeLock()

        val settings = RhVoiceApp.get().settingsRepository.settings.value
        val url = settings.url.trim()
        if (url.isEmpty()) {
            ConnectionStateHolder.set(ConnectionStatus.Error("No URL configured"))
            updateNotification("No URL configured")
            return
        }

        ConnectionStateHolder.set(ConnectionStatus.Connecting)

        val opts = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(15_000)
            .setAuth(
                mapOf(
                    "username" to settings.username,
                    "password" to settings.password,
                )
            )
            .build()

        val s = try {
            IO.socket(URI.create(url), opts)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build socket", e)
            ConnectionStateHolder.set(ConnectionStatus.Error(e.message ?: "Invalid URL"))
            updateNotification("Error: ${e.message ?: "Invalid URL"}")
            return
        }

        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Connected to $url")
            ConnectionStateHolder.set(ConnectionStatus.Connected)
            updateNotification("Connected")
            lastRaceStatus = -1
            s.emit(
                "load_data",
                JSONObject().put(
                    "load_types",
                    JSONArray(listOf("pilot_data", "race_status", "current_laps")),
                ),
            )
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString() ?: "disconnected"
            Log.i(TAG, "Disconnected: $reason")
            ConnectionStateHolder.set(ConnectionStatus.Connecting)
            updateNotification("Reconnecting… ($reason)")
            tones.cancel()
            lastRaceStatus = -1
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.let { describe(it) } ?: "connect error"
            Log.w(TAG, "Connect error: $msg")
            ConnectionStateHolder.set(ConnectionStatus.Error(msg))
            updateNotification("Error: $msg")
        }

        s.on("phonetic_data") { args ->
            RaceEvents.parsePhonetic(args)?.let { p ->
                val parts = buildList {
                    (p.pilot ?: p.callsign)?.let { add(it) }
                    p.lap?.let { add("Lap $it") }
                    add(p.phonetic)
                }
                speaker.speak(parts.joinToString(", "))
            }
        }
        s.on("stage_ready") { args ->
            RaceEvents.parseStageReady(args)?.let {
                Log.i(TAG, "stage_ready: tones=${it.stagingTones}, gap=${it.stagingGapSec}s")
                tones.scheduleStaging(it)
            }
        }
        s.on("phonetic_text") { args ->
            RaceEvents.parsePhoneticText(args)?.let { speaker.speak(it) }
        }
        s.on("race_status") { args ->
            val p = RaceEvents.parseRaceStatus(args) ?: return@on
            val prev = lastRaceStatus
            if (prev == p.status) return@on
            lastRaceStatus = p.status
            Log.i(TAG, "race_status $prev -> ${p.status} (staging_tones=${p.stagingTones}, gap=${p.stagingGapSec}s, race=${p.raceTimeSec}s, unlimited=${p.unlimitedTime})")
            when (p.status) {
                RaceEvents.STAGING -> Unit  // tones come from `stage_ready` which has the real timestamps
                RaceEvents.RACING -> Unit
                RaceEvents.DONE -> Unit     // let the local schedule finish — DONE arrives ~at race expiry and would race the end tone
                RaceEvents.READY -> tones.cancel()  // stop during staging — start tone must not fire after abort
            }
        }

        socket = s
        s.connect()
    }

    private fun describe(any: Any): String = when (any) {
        is Throwable -> any.message ?: any.javaClass.simpleName
        is JSONObject -> any.toString()
        else -> any.toString()
    }

    private fun stopSelfFully() {
        socket?.let {
            runCatching { it.disconnect() }
            runCatching { it.off() }
        }
        socket = null
        tones.cancel()
        lastRaceStatus = -1
        releaseWakeLock()
        ConnectionStateHolder.set(ConnectionStatus.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        socket?.let {
            runCatching { it.disconnect() }
            runCatching { it.off() }
        }
        socket = null
        runCatching { tones.shutdown() }
        runCatching { speaker.shutdown() }
        releaseWakeLock()
        if (ConnectionStateHolder.state.value !is ConnectionStatus.Stopped) {
            ConnectionStateHolder.set(ConnectionStatus.Stopped)
        }
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SocketService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RH-Voice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder system icon
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "RotorHazard connection status" }
            nm.createNotificationChannel(ch)
        }
    }

    // ---------- wake lock ----------

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "SocketService"
        private const val CHANNEL_ID = "rhvoice.connection"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "rhvoice:socket"
        const val ACTION_STOP = "com.rhvoice.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SocketService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
