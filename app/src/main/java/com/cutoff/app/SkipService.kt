package com.cutoff.app

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
import org.json.JSONObject

class SkipService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

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
        if (worker == null) {
            running = true
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cutoff:loop")
                .apply { setReferenceCounted(false) }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
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

    private fun setStatus(s: String) {
        if (Spotify.status != s) Spotify.status = s
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private fun loop() {
        var skippedId: String? = null
        var idleSince = System.currentTimeMillis()
        while (running) {
            var sleep = 10000L
            var active = false
            try {
                val r = Spotify.api("GET", "/me/player")
                when (r.code) {
                    204 -> {
                        setStatus("No active Spotify device. Start playing a song.")
                        sleep = 15000L
                    }
                    429 -> sleep = r.retryAfter * 1000L
                    401 -> {
                        setStatus("Login expired. Disconnect and connect again.")
                        sleep = 15000L
                    }
                    200 -> {
                        val j = JSONObject(r.body)
                        val item = j.optJSONObject("item")
                        if (item == null) {
                            setStatus("Nothing playing")
                            sleep = 15000L
                        } else {
                            val id = item.optString("uri")
                            val name = item.optString("name")
                            val dur = item.optLong("duration_ms")
                            val pos = j.optLong("progress_ms")
                            val playing = j.optBoolean("is_playing")
                            val cut = dur * Spotify.percent / 100
                            if (skippedId != null && (skippedId != id || pos < cut - 3000)) {
                                skippedId = null
                            }
                            if (!playing) {
                                setStatus("Spotify is paused")
                                sleep = 10000L
                            } else {
                                active = true
                                idleSince = System.currentTimeMillis()
                                if (id == skippedId) {
                                    setStatus("Skipping…")
                                    sleep = 1000L
                                } else {
                                    val remaining = cut - pos
                                    if (remaining <= 400) {
                                        val s = Spotify.api("POST", "/me/player/next")
                                        when (s.code) {
                                            200, 204 -> {
                                                skippedId = id
                                                Spotify.lastSkip = "Last skip: $name"
                                                sleep = 800L
                                            }
                                            403 -> {
                                                setStatus("Spotify Premium is required to skip.")
                                                sleep = 10000L
                                            }
                                            404 -> {
                                                setStatus("No active Spotify device.")
                                                sleep = 10000L
                                            }
                                            429 -> sleep = s.retryAfter * 1000L
                                            else -> sleep = 2000L
                                        }
                                    } else {
                                        setStatus("Watching: $name (skips at ${fmt(cut)})")
                                        // Check again just before the cutoff, otherwise every 10s.
                                        sleep = if (remaining > 10300L) 10000L
                                        else maxOf(200L, remaining - 300L)
                                    }
                                }
                            }
                        }
                    }
                    else -> sleep = 5000L
                }
            } catch (e: Exception) {
                setStatus("Connection problem. Retrying…")
                sleep = 5000L
            }

            hold(active)

            if (!active && System.currentTimeMillis() - idleSince > 30 * 60 * 1000L) {
                Spotify.lastSkip = "Stopped automatically after 30 minutes without music."
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
}
