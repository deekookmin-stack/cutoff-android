package com.cutoff.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var accessBox: LinearLayout
    private lateinit var setup: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var idBox: EditText
    private lateinit var statusTv: TextView
    private lateinit var lastTv: TextView
    private lateinit var pctTv: TextView
    private lateinit var startBtn: Button
    private lateinit var disconnectBtn: Button

    private lateinit var lfBox: LinearLayout
    private lateinit var lfList: LinearLayout
    private lateinit var lfAdd: LinearLayout
    private lateinit var lfStatus: TextView
    private lateinit var lfKey: EditText
    private lateinit var lfSecret: EditText
    private var lfSig = ""

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spotify.init(this)
        Lastfm.init(this)
        buildUi()
        handleIntent(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun tv(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, 0, 0, dp(12))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun listenerOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return s.contains(packageName)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        root.addView(tv("Cutoff", 32f, true))
        root.addView(tv("Skips each Spotify song once it reaches the percentage you choose.", 15f))

        // Mode switch
        val modeSwitch = Switch(this).apply {
            text = "Notification mode (read Spotify on this phone)"
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
            isChecked = Mode.isLocal(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Mode.setLocal(this@MainActivity, checked)
                refresh()
            }
        }
        root.addView(modeSwitch)
        root.addView(
            tv(
                "On: reads Spotify's player on this phone. No login or internet needed. " +
                    "It only looks at Spotify's player, never the text of your notifications.\n" +
                    "Off: asks Spotify's servers. Needs the login below.", 13f
            )
        )

        // Last.fm
        val lfSwitch = Switch(this).apply {
            text = "Scrobble to Last.fm"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            isChecked = Lastfm.enabled
            setOnCheckedChangeListener { _, checked ->
                Lastfm.enabled = checked
                refresh()
            }
        }
        root.addView(lfSwitch)

        lfBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lfStatus = tv("", 14f, true)
        lfBox.addView(lfStatus)
        lfBox.addView(
            tv(
                "Every play is sent to all the accounts below. Turn off Spotify's own Last.fm link, " +
                    "the Last.fm app and Pano Scrobbler, or plays will be counted twice. Songs only " +
                    "count when you hear at least half, so keep the cutoff at 50% or higher.", 13f
            )
        )

        lfList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lfBox.addView(lfList)

        lfAdd = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lfAdd.addView(
            tv(
                "Add an account. Create a free key at last.fm/api/account/create (any name, " +
                    "leave the callback blank), then paste the API key and Shared secret here. " +
                    "The same key works for every account.", 14f
            )
        )
        lfKey = EditText(this).apply {
            hint = "Last.fm API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(Lastfm.apiKey)
        }
        lfSecret = EditText(this).apply {
            hint = "Last.fm Shared secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(Lastfm.secret)
        }
        lfAdd.addView(lfKey)
        lfAdd.addView(lfSecret)
        lfAdd.addView(
            tv(
                "To add a different account, first log in to that account on last.fm in your " +
                    "browser (log out of the previous one, or use a private tab). The Last.fm page " +
                    "allows whichever account is logged in.", 13f
            )
        )
        lfAdd.addView(btn("1. Add a Last.fm account") {
            val k = lfKey.text.toString().trim()
            val s = lfSecret.text.toString().trim()
            if (k.isEmpty() || s.isEmpty()) {
                toast("Paste both the API key and the Shared secret.")
            } else {
                Lastfm.apiKey = k
                Lastfm.secret = s
                Thread {
                    val token = Lastfm.getToken()
                    runOnUiThread {
                        if (token == null) {
                            toast(Lastfm.note)
                        } else {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Lastfm.authUrl(token))))
                        }
                    }
                }.start()
            }
        })
        lfAdd.addView(tv("Allow Cutoff on the Last.fm page, then come back here.", 13f))
        lfAdd.addView(btn("2. I've allowed it, finish") {
            Thread {
                val ok = Lastfm.finishAuth()
                runOnUiThread {
                    toast(if (ok) "Last.fm account added." else Lastfm.note)
                    refresh()
                }
            }.start()
        })
        lfBox.addView(lfAdd)
        root.addView(lfBox)

        // Notification access help (shown only when needed)
        accessBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accessBox.addView(
            tv(
                "Notification access is off. Tap the first button and switch Cutoff on. " +
                    "If the switch is greyed out or says \"Restricted setting\", tap the second " +
                    "button, open the ⋮ menu at the top right, choose \"Allow restricted settings\", " +
                    "then try the first button again.", 14f
            )
        )
        accessBox.addView(btn("Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        accessBox.addView(btn("Open Cutoff's app info") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        // Server login setup
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(
            tv(
                "Server mode setup: in your Spotify developer dashboard, add this Redirect URI " +
                    "to your app: cutoff://callback\n\nThen paste your Client ID below.", 15f
            )
        )
        idBox = EditText(this).apply {
            hint = "Spotify Client ID"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(Spotify.clientId)
        }
        setup.addView(idBox)
        setup.addView(btn("Connect Spotify") {
            val id = idBox.text.toString().trim()
            if (id.length != 32) {
                toast("The Client ID should be 32 letters and numbers.")
            } else {
                Spotify.startAuth(this, id)
            }
        })

        // Main controls
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = tv("Stopped", 18f, true)
        lastTv = tv("", 14f)
        pctTv = tv("Skip after ${Spotify.percent}%", 18f, true)
        val seek = SeekBar(this).apply {
            max = 90
            progress = Spotify.percent - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Spotify.percent = p + 5
                    pctTv.text = "Skip after ${p + 5}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        startBtn = btn("Start") { toggleService() }
        disconnectBtn = btn("Disconnect Spotify login") {
            Spotify.logout()
            refresh()
        }

        main.addView(statusTv)
        main.addView(lastTv)
        main.addView(pctTv)
        main.addView(seek)
        main.addView(startBtn)
        main.addView(btn("Battery settings (set to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        main.addView(disconnectBtn)

        root.addView(accessBox)
        root.addView(setup)
        root.addView(main)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toggleService() {
        if (SkipService.running) {
            stopService(Intent(this, SkipService::class.java))
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, SkipService::class.java))
        }
        refresh()
    }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "cutoff") return
        setIntent(Intent())
        val code = d.getQueryParameter("code")
        if (code == null) {
            toast("Spotify login was cancelled.")
            return
        }
        Thread {
            val ok = try { Spotify.exchange(code) } catch (e: Exception) { false }
            runOnUiThread {
                if (!ok) toast("Login failed. Check the Client ID and Redirect URI.")
                refresh()
            }
        }.start()
    }

    // Rebuilds the list of Last.fm accounts (only when it changes).
    private fun rebuildAccounts() {
        lfList.removeAllViews()
        val list = Lastfm.accountList()
        for ((i, p) in list.withIndex()) {
            val label = if (p.second) "${p.first} (needs adding again)" else p.first
            lfList.addView(tv(label, 15f, true))
            lfList.addView(btn("Remove ${p.first}") {
                Lastfm.removeAccount(i)
                refresh()
            })
        }
    }

    private fun refresh() {
        val local = Mode.isLocal(this)
        val logged = Spotify.loggedIn
        accessBox.visibility = if (local && !listenerOn()) View.VISIBLE else View.GONE
        setup.visibility = if (!local && !logged) View.VISIBLE else View.GONE
        main.visibility = if (local || logged) View.VISIBLE else View.GONE
        disconnectBtn.visibility = if (logged) View.VISIBLE else View.GONE
        statusTv.text = if (SkipService.running) Spotify.status else "Stopped"
        lastTv.text = Spotify.lastSkip
        startBtn.text = if (SkipService.running) "Stop" else "Start"

        val lf = Lastfm.enabled
        lfBox.visibility = if (lf) View.VISIBLE else View.GONE
        if (lf) {
            val sig = Lastfm.accountList().joinToString("|") { it.first + it.second }
            if (sig != lfSig) {
                lfSig = sig
                rebuildAccounts()
            }
            lfAdd.visibility =
                if (Lastfm.accountCount >= Lastfm.MAX_ACCOUNTS) View.GONE else View.VISIBLE
            val head = "Accounts: ${Lastfm.accountCount} of ${Lastfm.MAX_ACCOUNTS}. " +
                "Waiting to send: ${Lastfm.pending}"
            lfStatus.text = head + (if (Lastfm.note.isNotEmpty()) "\n" + Lastfm.note else "")
        }
    }
}
