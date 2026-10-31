package com.cutoff.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object Lastfm {
    private lateinit var prefs: SharedPreferences
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("cutoff", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs.getString("lf_key", "") ?: ""
        set(v) { prefs.edit().putString("lf_key", v).apply() }

    var secret: String
        get() = prefs.getString("lf_secret", "") ?: ""
        set(v) { prefs.edit().putString("lf_secret", v).apply() }

    var user: String
        get() = prefs.getString("lf_user", "") ?: ""
        set(v) { prefs.edit().putString("lf_user", v).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean("lf_on", false)
        set(v) { prefs.edit().putBoolean("lf_on", v).apply() }

    var note: String
        get() = prefs.getString("lf_note", "") ?: ""
        set(v) { prefs.edit().putString("lf_note", v).apply() }

    private var session: String
        get() = prefs.getString("lf_session", "") ?: ""
        set(v) { prefs.edit().putString("lf_session", v).apply() }

    val connected: Boolean
        get() = session.isNotEmpty()

    fun disconnect() {
        session = ""
        user = ""
        note = ""
        prefs.edit().remove("lf_token").apply()
    }

    // ---------- Talking to Last.fm ----------

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Returns the HTTP code (0 if the network failed) and the JSON reply (if any).
    private fun call(params: Map<String, String>): Pair<Int, JSONObject?> {
        return try {
            val p = params.toMutableMap()
            p["api_key"] = apiKey
            val sig = md5(p.toSortedMap().entries.joinToString("") { it.key + it.value } + secret)
            p["api_sig"] = sig
            p["format"] = "json"
            val data = p.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
                .toByteArray()
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val json = try { JSONObject(body) } catch (e: Exception) { null }
            Pair(code, json)
        } catch (e: Exception) {
            Pair(0, null)
        }
    }

    private fun errText(code: Int, j: JSONObject?): String {
        if (code == 0) return "Couldn't reach Last.fm. Check your internet."
        val m = j?.optString("message") ?: ""
        return if (m.isNotEmpty()) "Last.fm says: $m" else "Last.fm problem (code $code)"
    }

    // ---------- Login (no password needed) ----------

    fun getToken(): String? {
        val (code, j) = call(mapOf("method" to "auth.getToken"))
        val t = j?.optString("token") ?: ""
        if (t.isEmpty()) {
            note = errText(code, j)
            return null
        }
        prefs.edit().putString("lf_token", t).apply()
        return t
    }

    fun authUrl(token: String) =
        "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    fun finishAuth(): Boolean {
        val t = prefs.getString("lf_token", "") ?: ""
        if (t.isEmpty()) {
            note = "Tap step 1 first."
            return false
        }
        val (code, j) = call(mapOf("method" to "auth.getSession", "token" to t))
        val s = j?.optJSONObject("session")
        if (s == null) {
            note = errText(code, j)
            return false
        }
        session = s.optString("key")
        user = s.optString("name")
        prefs.edit().remove("lf_token").apply()
        note = "Connected."
        return true
    }

    // ---------- Queue ----------

    @Synchronized
    private fun queue(): JSONArray =
        try { JSONArray(prefs.getString("lf_q", "[]")) } catch (e: Exception) { JSONArray() }

    @Synchronized
    private fun saveQueue(a: JSONArray) {
        prefs.edit().putString("lf_q", a.toString()).apply()
    }

    val pending: Int
        get() = queue().length()

    // Called by the skip service. This only saves locally, so it is instant.
    fun scrobble(artist: String, track: String, album: String, ts: Long, durSec: Long) {
        if (!enabled || !connected || artist.isBlank() || track.isBlank()) return
        val a = queue()
        a.put(
            JSONObject().put("a", artist).put("t", track).put("al", album)
                .put("ts", ts).put("d", durSec)
        )
        val out = JSONArray()
        for (i in maxOf(0, a.length() - 200) until a.length()) out.put(a.get(i))
        saveQueue(out)
    }

    // 0 = sent, 1 = rejected for good (drop it), 2 = try again later
    private fun send(o: JSONObject): Int {
        val p = mutableMapOf(
            "method" to "track.scrobble",
            "artist" to o.optString("a"),
            "track" to o.optString("t"),
            "timestamp" to o.optLong("ts").toString(),
            "sk" to session
        )
        val al = o.optString("al")
        if (al.isNotEmpty()) p["album"] = al
        val d = o.optLong("d")
        if (d > 0) p["duration"] = d.toString()

        val (code, j) = call(p)
        if (code == 0) return 2
        val err = j?.optInt("error", 0) ?: 0
        if (code == 200 && err == 0 && j != null) {
            note = "Last scrobble: " + o.optString("a") + " – " + o.optString("t")
            return 0
        }
        if (err == 9 || err == 4) {
            session = ""
            note = "Last.fm login expired. Connect again."
            return 2
        }
        if (err == 11 || err == 16 || err == 29 || code >= 500) return 2
        note = errText(code, j)
        return 1
    }

    fun flush() {
        if (!enabled || !connected) return
        var a = queue()
        var sent = 0
        while (a.length() > 0 && sent < 10) {
            val r = send(a.getJSONObject(0))
            if (r == 2) break
            val rest = JSONArray()
            for (i in 1 until a.length()) rest.put(a.get(i))
            a = rest
            sent++
        }
        saveQueue(a)
    }
}
