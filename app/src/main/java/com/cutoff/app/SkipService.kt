package com.cutoff.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SkipService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val clock = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    private var skippedId: String? = null
    private var idleSince = System.currentTimeMillis()
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Spotify.init(this)
        val channelId = "cutoff"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Cutoff", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Cutoff is running")
            .setContentText("Skipping songs at your chosen point")
            .setSmallIcon(android.R.drawable.ic_media_next)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        if (intent == null) {
            // The system killed the service and brought it back.
            Spotify.lastSkip = "Restarted by the system at ${clock.format(Date())}"
        }
        if (worker == null) {
            running = true
            idleSince = System.currentTimeMillis()
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cutoff:loop")
                .apply { setReferenceCounted(false) }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        hold(false)
        Spotify.init(this)
        Spotify.status = "Stopped"
        super.onDestroy()
    }

    // Keep the processor awake only while a song is actually playing.
    private fun hold(on: Boolean) {
        val w = wake ?: return
        if (on) {
            w.acquire(10 * 60 * 1000L)
        } else if (w.isHeld) {
            w.release()
        }
    }

    private fun now() = clock.format(Date())

    private fun setStatus(s: String) {
        Spotify.status = s + "\nChecked " + now()
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private fun markActive() {
        active = true
        idleSince = System.currentTimeMillis()
    }

    private fun loop() {
        while (running) {
            active = false
            var sleep: Long
            try {
                sleep = if (Mode.isLocal(this)) localStep() else serverStep()
            } catch (e: Exception) {
                setStatus("Something went wrong. Retrying…")
                sleep = 5000L
            }

            hold(active)

            if (!active && System.currentTimeMillis() - idleSince > 2 * 60 * 60 * 1000L) {
                Spotify.lastSkip = "Stopped automatically after 2 hours without music."
                stopSelf()
                break
            }

            try {
                Thread.sleep(sleep)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    // Shared decision logic for both modes. Returns how long to sleep.
    private fun decide(
        id: String,
        name: String,
        pos: Long,
        cut: Long,
        playing: Boolean,
        fast: Boolean,
        skip: () -> Long?
    ): Long {
        if (skippedId != null && (skippedId != id || pos < cut - 3000)) {
            skippedId = null
        }
        if (!playing) {
            setStatus("Spotify is paused")
            return 10000L
        }
        markActive()
        if (id == skippedId) {
            setStatus("Skipping…")
            return 1000L
        }
        val remaining = cut - pos
        if (remaining <= 400) {
            val failSleep = skip()
            if (failSleep != null) return failSleep
            skippedId = id
            Spotify.lastSkip = "Last skip: $name (${now()})"
            return 800L
        }
        val tag = if (fast) "Watching (notification mode)" else "Watching (server mode)"
        setStatus("$tag: $name (skips at ${fmt(cut)})")
        return if (fast) {
            if (remaining > 5300L) 5000L else maxOf(100L, remaining - 150L)
        } else {
            if (remaining > 10300L) 10000L else maxOf(200L, remaining - 300L)
        }
    }

    // ---------- Local mode: read Spotify's media player on this phone ----------
    private fun localStep(): Long {
        val msm = getSystemService(MediaSessionManager::class.java)
        val sessions = try {
            msm.getActiveSessions(ComponentName(this, NotifService::class.java))
        } catch (e: SecurityException) {
            setStatus("Notification access is off. Open Cutoff and allow it.")
            return 10000L
        }
        val c = sessions.firstOrNull {
            it.packageName == "com.spotify.music" || it.packageName == "com.spotify.lite"
        }
        if (c == null) {
            setStatus("No Spotify player found. Start playing a song in Spotify.")
            return 10000L
        }
        val st = c.playbackState
        val md = c.metadata
        if (st == null || md == null) {
            setStatus("Spotify gave no playback info yet.")
            return 5000L
        }
        val playing = st.state == PlaybackState.STATE_PLAYING
        val buffering = st.state == PlaybackState.STATE_BUFFERING
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val name = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: (name + dur)

        if (buffering) {
            markActive()
            setStatus("Spotify is loading…")
            return 2000L
        }
        if (name == "Advertisement") {
            markActive()
            setStatus("An ad is playing")
            return 3000L
        }
        if (playing && dur <= 0L) {
            markActive()
            setStatus("Playing: $name (length unknown, can't skip by percentage)")
            return 5000L
        }

        var pos = st.position
        if (playing && st.position >= 0) {
            pos = st.position +
                ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (pos < 0) pos = 0
        if (dur > 0 && pos > dur) pos = dur
        val cut = dur * Spotify.percent / 100

        return decide(id, name, pos, cut, playing, true) {
            c.transportControls.skipToNext()
            null
        }
    }

    // ---------- Server mode: ask Spotify's servers ----------
    private fun serverStep(): Long {
        if (!Spotify.loggedIn) {
            setStatus("Not connected to Spotify. Connect in the app, or switch on notification mode.")
            return 10000L
        }
        val r = Spotify.api("GET", "/me/player?additional_types=episode")
        when (r.code) {
            200 -> {}
            204 -> {
                setStatus("Spotify isn't reporting an active device. Open Spotify and play a song.")
                return 10000L
            }
            429 -> return r.retryAfter * 1000L
            401 -> {
                setStatus("Login expired. Disconnect and connect again.")
                return 15000L
            }
            else -> {
                setStatus("Spotify replied with code ${r.code}")
                return 5000L
            }
        }
        val j = JSONObject(r.body)
        val item = j.optJSONObject("item")
        val type = j.optString("currently_playing_type")
        val playing = j.optBoolean("is_playing")
        if (item == null) {
            if (type == "ad" && playing) {
                markActive()
                setStatus("An ad is playing")
                return 5000L
            }
            setStatus("Nothing playing")
            return 10000L
        }
        val id = item.optString("uri")
        val name = item.optString("name")
        val dur = item.optLong("duration_ms")
        val pos = j.optLong("progress_ms")
        val cut = dur * Spotify.percent / 100

        return decide(id, name, pos, cut, playing, false) {
            val s = Spotify.api("POST", "/me/player/next")
            when (s.code) {
                200, 204 -> null
                403 -> {
                    setStatus("Spotify Premium is required to skip.")
                    10000L
                }
                404 -> {
                    setStatus("No active Spotify device.")
                    10000L
                }
                429 -> s.retryAfter * 1000L
                else -> 2000L
            }
        }
    }
}
