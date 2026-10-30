package com.cutoff.app

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

object Mode {
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("cutoff", Context.MODE_PRIVATE)

    fun isLocal(ctx: Context): Boolean = p(ctx).getBoolean("local", false)

    fun setLocal(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("local", v).apply()
    }
}

// This service reads nothing. Having it enabled is what lets Cutoff see
// Spotify's media player. It never looks at notification contents.
class NotifService : NotificationListenerService() {
    override fun onListenerDisconnected() {
        try {
            requestRebind(ComponentName(this, NotifService::class.java))
        } catch (e: Exception) {
        }
    }
}
