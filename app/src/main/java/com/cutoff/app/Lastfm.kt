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
    const val MAX_ACCOUNTS = 5
    private lateinit var prefs: SharedPreferences
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("cutoff", Context.MODE_PRIVATE)
            migrate()
        }
    }

    // Moves the old single account into the new list.
    @Synchronized
    private fun migrate() {
        val old = prefs.getString("lf_session", "") ?: ""
        if (old.isNotEmpty() && !prefs.contains("lf_accts")) {
            val q = try { JSONArray(prefs.getString("lf_q", "[]")) } catch (e: Exception) { JSONArray() }
            val o = JSONObject()
                .put("u", prefs.getString("lf_user", "") ?: "")
                .put("sk", old)
                .put("bad", false)
                .put("q", q)
            prefs.edit().putString("lf_accts", JSONArray().put(o).toString()).apply()
        }
        prefs.edit().remove("lf_session").remove("lf_q").apply()
    }

    var apiKey: String
        get() = prefs.getString("lf_key", "") ?: ""
        set(v) { prefs.edit().putString("lf_key", v).apply() }

    var secret: String
        get() = prefs.getString("lf_secret", "") ?: ""
        set(v) { prefs.edit().putString("lf_secret", v).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean("lf_on", false)
        set(v) { prefs.edit().putBoolean("lf_on", v).apply() }

    var note: String
        get() = prefs.getString("lf_note", "") ?: ""
        set(v) { prefs.edit().putString("lf_note", v).apply() }

    // ---------- Accounts ----------

    @Synchronized
    private fun accounts(): JSONArray =
        try { JSONArray(prefs.getString("lf_accts", "[]")) } catch (e: Exception) { JSONArray() }

    @Synchronized
    private fun saveAccounts(a: JSONArray) {
        prefs.edit().putString("lf_accts", a.toString()).apply()
    }

    val accountCount: Int
        get() = accounts().length()

    // True if at least one account is logged in and working.
    val connected: Boolean
        get() {
            val a = accounts()
            for (i in 0 until a.length()) {
                if (!a.getJSONObject(i).optBoolean("bad")) return true
            }
            return false
        }

    val pending: Int
        get() {
            val a = accounts()
            var n = 0
            for (i in 0 until a.length()) {
                n += a.getJSONObject(i).optJSONArray("q")?.length() ?: 0
            }
            return n
        }

    // Name and "needs reconnecting" flag for each account.
    fun accountList(): List<Pair<String, Boolean>> {
        val a = accounts()
        val out = ArrayList<Pair<String, Boolean>>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Pair(o.optString("u"), o.optBoolean("bad")))
        }
        return out
    }

    @Synchronized
    fun removeAccount(index: Int) {
        val a = accounts()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            if (i != index) out.put(a.get(i))
        }
        saveAccounts(out)
    }

    @Synchronized
    private fun addAccount(name: String, sk: String): Boolean {
        val a = accounts()
        var idx = -1
        for (i in 0 until a.length()) {
            if (a.getJSONObject(i).optString("u").equals(name, ignoreCase = true)) idx = i
        }
        if (idx < 0 && a.length() >= MAX_ACCOUNTS) {
            note = "You can add up to $MAX_ACCOUNTS accounts. Remove one first."
            return false
        }
        val o = if (idx >= 0) a.getJSONObject(idx) else JSONObject().put("q", JSONArray())
        o.put("u", name).put("sk", sk).put("bad", false)
        if (idx < 0) a.put(o)
        saveAccounts(a)
        return true
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
        val name = s.optString("name")
        val ok = addAccount(name, s.optString("key"))
        prefs.edit().remove("lf_token").apply()
        if (ok) note = "Connected: $name"
        return ok
    }

    // ---------- Scrobbling ----------

    // Called by the skip service. Saves locally for every account, so it is instant.
    @Synchronized
    fun scrobble(artist: String, track: String, album: String, ts: Long, durSec: Long) {
        if (!enabled || artist.isBlank() || track.isBlank()) return
        val a = accounts()
        if (a.length() == 0) return
        for (i in 0 until a.length()) {
            val acct = a.getJSONObject(i)
            val q = acct.optJSONArray("q") ?: JSONArray()
            q.put(
                JSONObject().put("a", artist).put("t", track).put("al", album)
                    .put("ts", ts).put("d", durSec)
            )
            val trimmed = JSONArray()
            for (k in maxOf(0, q.length() - 200) until q.length()) trimmed.put(q.get(k))
            acct.put("q", trimmed)
        }
        saveAccounts(a)
    }

    // 0 = sent, 1 = rejected for good (drop it), 2 = try again later, 3 = login expired
    private fun send(sk: String, o: JSONObject, user: String): Int {
        val p = mutableMapOf(
            "method" to "track.scrobble",
            "artist" to o.optString("a"),
            "track" to o.optString("t"),
            "timestamp" to o.optLong("ts").toString(),
            "sk" to sk
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
            note = "Last.fm login expired for $user. Add that account again."
            return 3
        }
        if (err == 11 || err == 16 || err == 29 || code >= 500) return 2
        note = errText(code, j)
        return 1
    }

    // Drops the first `dropped` plays from one account's queue and updates its state.
    @Synchronized
    private fun applyResult(user: String, dropped: Int, bad: Boolean) {
        val a = accounts()
        for (i in 0 until a.length()) {
            val acct = a.getJSONObject(i)
            if (acct.optString("u") != user) continue
            val q = acct.optJSONArray("q") ?: JSONArray()
            val rest = JSONArray()
            for (k in dropped until q.length()) rest.put(q.get(k))
            acct.put("q", rest)
            if (bad) acct.put("bad", true)
        }
        saveAccounts(a)
    }

    fun flush() {
        if (!enabled) return
        val snap = accounts()
        for (i in 0 until snap.length()) {
            val acct = snap.getJSONObject(i)
            if (acct.optBoolean("bad")) continue
            val user = acct.optString("u")
            val sk = acct.optString("sk")
            val q = acct.optJSONArray("q") ?: continue
            var done = 0
            var bad = false
            while (done < q.length() && done < 10) {
                val r = send(sk, q.getJSONObject(done), user)
                if (r == 2) break
                if (r == 3) {
                    bad = true
                    break
                }
                done++
            }
            if (done > 0 || bad) applyResult(user, done, bad)
        }
    }
}
