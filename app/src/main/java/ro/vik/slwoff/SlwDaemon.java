package ro.vik.slwoff;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Daemon pornit prin ADB ca uid shell (2000):
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=slwoff_daemon ro.vik.slwoff.SlwDaemon
 *
 * Logica extrasă din OverDrive (MIT, Yash Srivastava):
 *   AccSentryDaemon (listener ACC pe BYDAutoBodyworkDevice), BydDataCollector.setSpeedLimitWarning,
 *   BydDeviceHelper, BydFeatureIds, DaemonBootstrap / PermissionBypassContext.
 *
 * Ce face: NU atinge SLW în mod continuu. Ascultă power level-ul mașinii
 * (0=OFF, 1=ACC, 2=ON, 3=OK/READY). La trecerea în ON (și o dată la pornirea tabletei,
 * dacă mașina e deja pornită) deschide o fereastră de WINDOW_MS în care verifică SLW
 * și îl stinge dacă e pornit. După fereastră nu mai face nimic până la următoarea pornire,
 * deci dacă îl activezi tu manual în timpul mersului, rămâne activ.
 *
 * Fereastra există pentru că ECU-ul ADAS se trezește la câteva secunde după contact
 * și își restaurează singur SLW=ON — o singură scriere la fix ON s-ar pierde.
 *
 * Ascultă pe 127.0.0.1:PORT comenzi text: PING, NOW (rulează fereastra acum), STOP.
 */
public final class SlwDaemon {

    public static final int PORT = 47811;
    private static final long WINDOW_MS = 90_000;   // cât timp după pornire supraveghem SLW
    private static final long CHECK_MS = 2_000;     // pas de verificare în fereastră
    private static final long BACKUP_POLL_MS = 15_000; // citire power level, în caz că se pierde un callback
    private static final String PKG = "ro.vik.slwoff";
    private static final String ADAS_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice";
    private static final String BODY_CLASS = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";

    // ID-uri: rezolvate din BYDAutoFeatureIds dacă există pe firmware, altfel fallback-urile din OverDrive.
    private static final int SLW_STATE = resolveId("Adas", "ADAS_SLW_FUNC_SWITCH_STATE", 535834664);
    private static final int SLW_SET = resolveId("Adas", "ADAS_SLW_FUNC_SWITCH_STATE_SET", 850452531);
    private static final int ISLA_SET = resolveId("Adas", "ADAS_ISLA_SWITCH_SET", 944767044);

    private static final int OFF = 1;
    private static final int ON = 2;
    private static final int POWER_ON = 2;

    private static volatile boolean running = true;
    private static volatile int powerLevel = -1;
    private static volatile int lastState = -1;
    private static volatile int offCount = 0;
    private static volatile String lastAction = "-";
    private static volatile long windowUntil = 0;   // elapsedRealtime până la care supraveghem
    private static final Object lock = new Object();

    private static Context ctx;
    private static Object adas;
    private static Object body;
    private static Object listener; // referință tare, să nu fie colectat

    public static void main(String[] args) {
        ServerSocket server;
        try {
            // Port-ul e și lock de instanță unică.
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            log("Altă instanță rulează deja (port " + PORT + " ocupat). Ies.");
            System.exit(0);
            return;
        }
        log("=== SLW-Off daemon pornit, uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid() + " ===");
        log("IDs: SLW_STATE=" + SLW_STATE + " SLW_SET=" + SLW_SET + " ISLA_SET=" + ISLA_SET);

        try { Looper.prepareMainLooper(); } catch (Throwable ignored) {}
        ctx = createContext();

        final ServerSocket srv = server;
        Thread ctl = new Thread(() -> controlLoop(srv), "slw-ctl");
        ctl.setDaemon(true);
        ctl.start();

        // Listener-ul se creează pe main thread (are Looper) — SDK-ul BYD poate crea Handler-e.
        registerPowerListener();

        Thread worker = new Thread(SlwDaemon::workerLoop, "slw-worker");
        worker.start();

        Looper.loop(); // callback-urile listener-ului BYD vin pe looper-ul principal
    }

    // ---------------------------------------------------------------- power level

    /** Apelat din listener sau din backup poll. Trigger doar pe front crescător spre ON. */
    static void onPower(int level, String src) {
        int prev = powerLevel;
        if (level == prev) return;
        powerLevel = level;
        log("power " + name(prev) + " -> " + name(level) + " (" + src + ")");
        if (level >= POWER_ON && (prev < POWER_ON)) {
            // prev == -1 = prima citire (tableta tocmai a pornit cu mașina deja pornită)
            openWindow(prev < 0 ? "tabletă pornită cu mașina ON" : "pornire motor");
        }
    }

    static void openWindow(String why) {
        synchronized (lock) {
            windowUntil = android.os.SystemClock.elapsedRealtime() + WINDOW_MS;
            lock.notifyAll();
        }
        log("fereastră SLW deschisă " + (WINDOW_MS / 1000) + "s: " + why);
    }

    private static boolean registerPowerListener() {
        try {
            body = Class.forName(BODY_CLASS).getMethod("getInstance", Context.class).invoke(null, ctx);
            if (body == null) { log("Bodywork device NULL"); return false; }
            listener = new PowerListener();
            Class<?> lc = Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
            body.getClass().getMethod("registerListener", lc).invoke(body, listener);
            log("listener power level înregistrat");
            return true;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("listener eșuat (rămâne backup poll): " + c);
            return false;
        }
    }

    private static int readPower() {
        try {
            if (body == null) {
                body = Class.forName(BODY_CLASS).getMethod("getInstance", Context.class).invoke(null, ctx);
            }
            Object r = body.getClass().getMethod("getPowerLevel").invoke(body);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            body = null;
            return -1;
        }
    }

    static final class PowerListener extends android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener {
        @Override public void onPowerLevelChanged(int level) {
            if (level >= 0 && level <= 3) onPower(level, "listener");
        }
    }

    // ---------------------------------------------------------------- worker

    private static void workerLoop() {
        int p = readPower();
        if (p >= 0) onPower(p, "citire inițială");

        long nextBackup = 0;
        while (running) {
            long now = android.os.SystemClock.elapsedRealtime();

            if (now >= nextBackup) {
                int lvl = readPower();
                if (lvl >= 0 && lvl <= 3) onPower(lvl, "backup poll");
                nextBackup = now + BACKUP_POLL_MS;
            }

            if (now < windowUntil) {
                try {
                    if (adas == null) adas = acquireAdas();
                    if (adas != null) {
                        int st = getInt(adas, SLW_STATE);
                        if (st != lastState) log("SLW state: " + lastState + " -> " + st);
                        lastState = st;
                        if (st == ON) turnOff("SLW=ON după pornire");
                        else if (st != OFF && offCount == 0) turnOff("stare necitibilă (" + st + "), scriere oarbă");
                    }
                } catch (NoSuchMethodException t) {
                    log("check error: " + t); // metodă lipsă — device-ul e ok, nu-l re-achiziționăm
                } catch (Throwable t) {
                    log("check error: " + t);
                    adas = null;
                }
                sleepLock(CHECK_MS);
            } else {
                sleepLock(Math.max(500, nextBackup - now));
            }
        }
        log("worker oprit");
        System.exit(0);
    }

    private static void turnOff(String why) {
        int rc = setInt(adas, SLW_SET, OFF);
        String how = "SLW_SET rc=" + rc;
        if (rc < 0) {
            int rc2 = setInt(adas, ISLA_SET, OFF);
            how += ", ISLA_SET rc=" + rc2;
        }
        offCount++;
        lastAction = now() + " " + why + " -> " + how;
        log("OFF: " + lastAction);
    }

    // ---------------------------------------------------------------- control socket

    private static void controlLoop(ServerSocket srv) {
        while (running) {
            try (Socket s = srv.accept()) {
                s.setSoTimeout(2000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String cmd = in.readLine();
                String reply;
                if (cmd == null) cmd = "";
                switch (cmd.trim().toUpperCase(Locale.ROOT)) {
                    case "NOW":  openWindow("manual din aplicație"); reply = "OK window"; break;
                    case "STOP": running = false; reply = "OK stopping"; log("STOP"); break;
                    default:
                        long left = windowUntil - android.os.SystemClock.elapsedRealtime();
                        reply = "OK power=" + name(powerLevel) + " slw=" + lastState
                                + " window=" + (left > 0 ? (left / 1000) + "s" : "închisă")
                                + " offs=" + offCount + " last=" + lastAction;
                }
                OutputStream out = s.getOutputStream();
                out.write((reply + "\n").getBytes("UTF-8"));
                out.flush();
                if (!running) {
                    try { srv.close(); } catch (Exception ignored) {}
                    System.exit(0);
                }
            } catch (Throwable t) {
                if (running) log("ctl error: " + t);
            }
        }
    }

    private static String name(int l) {
        switch (l) {
            case 0: return "OFF"; case 1: return "ACC"; case 2: return "ON"; case 3: return "OK";
            default: return "?";
        }
    }

    private static void sleepLock(long ms) {
        synchronized (lock) {
            try { lock.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    // ---------------------------------------------------------------- BYD SDK (reflection)

    private static Object acquireAdas() {
        try {
            Class<?> cls = Class.forName(ADAS_CLASS);
            Object dev = cls.getMethod("getInstance", Context.class).invoke(null, ctx);
            log(dev != null ? "ADAS device OK" : "ADAS device NULL");
            return dev;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("ADAS device indisponibil: " + c);
            return null;
        }
    }

    /** Ca BydDeviceHelper.findMethodCached: get/set sunt protected în AbsBYDAutoDevice → walk + setAccessible. */
    private static Method find(Object dev, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = dev.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(dev.getClass().getName() + "." + name);
    }

    private static int deviceType(Object dev) throws Exception {
        for (String name : new String[]{"getDevicetype", "getType"}) {
            try {
                Object r = find(dev, name).invoke(dev);
                if (r instanceof Number) return ((Number) r).intValue();
            } catch (NoSuchMethodException ignored) {}
        }
        throw new IllegalStateException("nu pot afla deviceType");
    }

    private static int getInt(Object dev, int id) throws Exception {
        Method m = find(dev, "get", int.class, int.class);
        Object r = m.invoke(dev, deviceType(dev), id);
        return r instanceof Number ? ((Number) r).intValue() : -1;
    }

    /** Ca BydDeviceHelper.sendSetCommandRaw: set(int[] ids, BYDAutoEventValue); >=0 = acceptat. */
    private static int setInt(Object dev, int id, int value) {
        try {
            Class<?> ev = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object v = ev.getConstructor().newInstance();
            ev.getField("intValue").setInt(v, value);
            Object r = find(dev, "set", int[].class, ev).invoke(dev, new int[]{id}, v);
            if (r instanceof Integer) return (Integer) r;
            if (r instanceof Boolean) return ((Boolean) r) ? 0 : -1;
            return 0;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // fallback: set(int deviceType, int id, int value)
            try {
                Object r = find(dev, "set", int.class, int.class, int.class)
                        .invoke(dev, deviceType(dev), id, value);
                return r instanceof Number ? ((Number) r).intValue() : 0;
            } catch (Throwable t) {
                log("set fallback error id=" + id + ": " + t);
                return Integer.MIN_VALUE;
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("set error id=" + id + ": " + c);
            return Integer.MIN_VALUE;
        }
    }

    private static int resolveId(String inner, String field, int fallback) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            for (Class<?> c : cls.getDeclaredClasses()) {
                if (c.getSimpleName().equals(inner)) return c.getField(field).getInt(null);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    // ---------------------------------------------------------------- context (DaemonBootstrap)

    private static Context createContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try { thread = at.getMethod("currentActivityThread").invoke(null); } catch (Throwable ignored) {}
            if (thread == null) thread = at.getMethod("systemMain").invoke(null);
            Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
            Context pkg = sys.createPackageContext(PKG,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            log("context OK: " + pkg.getPackageName());
            return new BypassContext(pkg);
        } catch (Throwable t) {
            log("createContext failed: " + t);
            return new BypassContext(null);
        }
    }

    /** SDK-ul BYD verifică permisiunile pe Context; le raportăm acordate. */
    static final class BypassContext extends ContextWrapper {
        BypassContext(Context base) { super(base); }
        @Override public void enforceCallingOrSelfPermission(String p, String m) {}
        @Override public void enforcePermission(String p, int pid, int uid, String m) {}
        @Override public void enforceCallingPermission(String p, String m) {}
        @Override public int checkCallingOrSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String p, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
    }

    // ---------------------------------------------------------------- utils

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static void log(String s) {
        // stdout e redirecționat de launcher în /data/local/tmp/slwoff.log
        System.out.println(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + s);
        System.out.flush();
    }
}
