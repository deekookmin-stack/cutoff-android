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

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Spotify.init(this)
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
    }
}
