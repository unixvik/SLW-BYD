package ro.vik.slwoff

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootReceiver.scheduleWatchdog(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply { textSize = 20f; text = "…" }
        root.addView(status)

        val toggle = android.widget.Switch(this).apply {
            text = "Activ (oprește SLW la pornirea motorului)"
            textSize = 18f
            isChecked = Launcher.isEnabled(applicationContext)
            setOnCheckedChangeListener { _, on -> exec { Launcher.setEnabled(applicationContext, on) } }
        }
        root.addView(toggle)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: () -> String) = Button(this).apply {
            text = label
            setOnClickListener { exec(action) }
            row.addView(this)
        }
        btn("Pornește") { Launcher.ensureRunning(applicationContext) }
        btn("Oprește SLW acum") { Launcher.send("NOW") ?: "Daemon oprit" }
        btn("Log") { log(); "" }
        root.addView(row)

        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        exec { Launcher.ensureRunning(applicationContext) }
    }

    private fun exec(action: () -> String) {
        status.text = "…"
        Thread {
            val msg = action()
            val ping = Launcher.send("PING")
            runOnUiThread {
                status.text = (if (msg.isNotEmpty()) "$msg\n" else "") +
                        (ping?.let { "Daemon: $it" } ?: "Daemon: nu rulează")
            }
        }.start()
    }

    private fun log() {
        Thread {
            val txt = Launcher.readLog(applicationContext)
            runOnUiThread { log.text = txt }
        }.start()
    }
}
