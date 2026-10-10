package com.cutoff.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Resp(val code: Int, val body: String, val retryAfter: Int)

object Spotify {
    const val REDIRECT = "cutoff://callback"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("cutoff", Context.MODE_PRIVATE)
        }
    }

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(v) { prefs.edit().putString("client_id", v).apply() }

    var percent: Int
        get() = prefs.getInt("pct", 60)
        set(v) { prefs.edit().putInt("pct", v).apply() }

    var status: String
        get() = prefs.getString("status", "Stopped") ?: "Stopped"
        set(v) { prefs.edit().putString("status", v).apply() }

    var lastSkip: String
        get() = prefs.getString("last", "") ?: ""
        set(v) { prefs.edit().putString("last", v).apply() }

    val loggedIn: Boolean
        get() = prefs.getString("refresh", null) != null

    fun logout() {
        prefs.edit().remove("access").remove("refresh").remove("expires").apply()
    }

    fun startAuth(ctx: Context, id: String) {
        clientId = id
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        val verifier = (1..64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("verifier", verifier).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun exchange(code: String): Boolean {
        val r = postForm(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT,
                "client_id" to clientId,
                "code_verifier" to (prefs.getString("verifier", "") ?: "")
            )
        )
        if (r.code != 200) return false
        saveTokens(JSONObject(r.body))
        return true
    }

    @Synchronized
    private fun token(): String? {
        if (System.currentTimeMillis() > prefs.getLong("expires", 0L)) {
            val rt = prefs.getString("refresh", null) ?: return null
            val r = postForm(
                mapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to clientId)
            )
            if (r.code != 200) return null
            saveTokens(JSONObject(r.body))
        }
        return prefs.getString("access", null)
    }

    private fun saveTokens(j: JSONObject) {
        val e = prefs.edit()
        e.putString("access", j.getString("access_token"))
        e.putLong("expires", System.currentTimeMillis() + j.getLong("expires_in") * 1000 - 60000)
        if (j.has("refresh_token")) e.putString("refresh", j.getString("refresh_token"))
        e.apply()
    }

    fun api(method: String, path: String): Resp {
        val t = token() ?: return Resp(401, "", 3)
        val c = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Authorization", "Bearer $t")
        if (method == "POST" || method == "PUT") {
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
        }
        return read(c)
    }

    private fun postForm(params: Map<String, String>): Resp {
        val data = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray()
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(data) }
        return read(c)
    }

    private fun read(c: HttpURLConnection): Resp {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val ra = c.getHeaderField("Retry-After")?.toIntOrNull() ?: 3
        c.disconnect()
        return Resp(code, body, ra)
    }
}
