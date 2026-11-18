package ro.vik.slwoff

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pornește SlwDaemon ca uid shell prin ADB-ul local (127.0.0.1:5555), la fel ca OverDrive.
 * Prima conectare cere pe ecran „Allow USB debugging?” → bifează „Always allow”.
 */
object Launcher {

    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    const val LOG = "/data/local/tmp/slwoff.log"

    /** Trimite o comandă daemon-ului; null dacă nu rulează. */
    fun send(cmd: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", SlwDaemon.PORT), 1000)
            s.soTimeout = 2000
            s.getOutputStream().write("$cmd\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine()
        }
    } catch (e: Exception) {
        null
    }

    fun isRunning(): Boolean = send("PING") != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slwoff", Context.MODE_PRIVATE)

    /** Comutatorul principal. Dezactivat = daemon oprit și nu mai e repornit la boot/watchdog. */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean): String {
        prefs(ctx).edit().putBoolean("enabled", on).commit()
        return if (on) ensureRunning(ctx)
        else if (send("STOP") != null) "Dezactivat. Daemon oprit." else "Dezactivat."
    }

    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun adb(ctx: Context): Dadb =
        Dadb.create(ADB_HOST, ADB_PORT, keyPair(ctx), 3_000, 20_000)

    /** Returnează un mesaj de stare. Blocant — apelează de pe un thread de fundal. */
    fun ensureRunning(ctx: Context): String {
        if (!isEnabled(ctx)) return "Aplicația e dezactivată."
        if (isRunning()) return "Daemon-ul rulează deja."
        return try {
            adb(ctx).use { d ->
                val apk = d.shell("pm path ${ctx.packageName}").output
                    .lineSequence().map { it.trim() }
                    .firstOrNull { it.startsWith("package:") }
                    ?.removePrefix("package:")
                    ?: return "Nu găsesc calea APK-ului (pm path)."
                // (… &) → subshell care iese imediat; app_process e re-parentat la init.
                val cmd = "(CLASSPATH=$apk app_process /system/bin --nice-name=slwoff_daemon " +
                        "ro.vik.slwoff.SlwDaemon </dev/null >>$LOG 2>&1 &) </dev/null >/dev/null 2>&1"
                d.shell(cmd)
            }
            Thread.sleep(2500)
            if (isRunning()) "Daemon pornit." else "Comanda a fost trimisă, dar daemon-ul nu răspunde. Vezi logul."
        } catch (e: Exception) {
            "Eroare ADB (${e.javaClass.simpleName}: ${e.message}). Wireless ADB e activ? Ai acceptat promptul?"
        }
    }

    fun readLog(ctx: Context, lines: Int = 40): String = try {
        adb(ctx).use { it.shell("tail -n $lines $LOG").output }
    } catch (e: Exception) {
        "Nu pot citi logul: ${e.message}"
    }
}
