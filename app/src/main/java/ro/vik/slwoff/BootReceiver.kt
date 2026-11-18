package ro.vik.slwoff

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Pornire automată: la boot, la update-ul aplicației și periodic (watchdog la 5 min),
 * ca daemon-ul să fie repornit dacă adbd/sistemul l-a omorât (ex. după sleep-ul tabletei).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        scheduleWatchdog(app)
        if (!Launcher.isEnabled(app)) return // dezactivat din aplicație → nu pornim nimic
        val pending = goAsync()
        Thread {
            try {
                // La boot, adbd poate să nu fie gata imediat → reîncercăm ~2 minute.
                repeat(12) { i ->
                    val msg = Launcher.ensureRunning(app)
                    Log.i(TAG, "${intent.action} try $i: $msg")
                    if (Launcher.isRunning()) return@Thread
                    Thread.sleep(10_000)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "SlwOff"
        const val ACTION_WATCHDOG = "ro.vik.slwoff.WATCHDOG"

        fun scheduleWatchdog(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, BootReceiver::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                5 * 60_000L,
                pi
            )
        }
    }
}
