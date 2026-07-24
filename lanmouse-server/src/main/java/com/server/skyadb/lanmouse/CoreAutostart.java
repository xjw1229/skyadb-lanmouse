package com.server.skyadb.lanmouse;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Launches /data/local/tmp/skyadb-lanmouse/start-core.sh after boot when enabled. */
final class CoreAutostart {
    private static final String TAG = "SkyADB-Autostart";
    private static final String PREFS = "skyadb_lanmouse_autostart";
    private static final String KEY_ENABLED = "enabled";
    private static final String START_SCRIPT = "/data/local/tmp/skyadb-lanmouse/start-core.sh";
    private static final String AUTOSTART_FLAG = "/data/local/tmp/skyadb-lanmouse/autostart.enabled";
    private static final String ROOT_AUTOSTART_FLAG = "/data/local/tmp/skyadb-lanmouse/root-autostart.enabled";
    private static final String ROOT_SERVICE_SCRIPT = "/data/adb/service.d/skyadb-lanmouse.sh";
    private static final String CORE_JAR = "/data/local/tmp/skyadb-lanmouse/skyadb-lanmouse-core.jar";
    private static final String PORT_HEX = ":4D9E";
    private static final long ROOT_COMMAND_TIMEOUT_MS = 15_000L;

    private CoreAutostart() {}

    static void enable(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLED, true).apply();
        writeFlag();
        Log.i(TAG, "autostart enabled");
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
            || new File(AUTOSTART_FLAG).isFile();
    }

    static boolean startIfEnabled(Context context) {
        boolean enabled = isEnabled(context);
        if (!enabled) {
            Log.i(TAG, "autostart disabled, skip");
            return false;
        }
        if (isPortListening()) {
            Log.i(TAG, "core already listening on 19870");
            return true;
        }
        if (!new File(CORE_JAR).isFile()) {
            Log.w(TAG, "core jar missing, skip");
            return false;
        }
        writeFlag();
        installRootServiceIfAvailable();
        boolean started = tryStartWithSu() || tryStartWithSh();
        Log.i(TAG, started ? "core start requested" : "core start failed");
        if (!started) {
            return false;
        }
        return waitForPort(4_000L);
    }

    static boolean installRootServiceIfAvailable() {
        String command = "mkdir -p /data/adb/service.d /data/local/tmp/skyadb-lanmouse\n"
            + "cat > " + ROOT_SERVICE_SCRIPT + " <<'SKYADB_ROOT_EOF'\n"
            + ROOT_SERVICE_SCRIPT_CONTENT
            + "SKYADB_ROOT_EOF\n"
            + "chmod 0755 " + ROOT_SERVICE_SCRIPT + "\n"
            + "chown 0:0 " + ROOT_SERVICE_SCRIPT + " 2>/dev/null || true\n"
            + "echo 1 > " + ROOT_AUTOSTART_FLAG + "\n"
            + "(sh " + ROOT_SERVICE_SCRIPT + " >/dev/null 2>&1 </dev/null &)\n"
            + "exit 0\n";
        boolean installed = runRootCommand(new String[]{"su", "-c", command})
            || runRootCommand(new String[]{"su", "0", "sh", "-c", command});
        Log.i(TAG, "root service.d install result=" + installed);
        return installed;
    }

    static boolean isPortListening() {
        return isLocalPortReachable() || fileContainsPort("/proc/net/tcp") || fileContainsPort("/proc/net/tcp6");
    }

    private static boolean waitForPort(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            if (isPortListening()) {
                return true;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private static boolean fileContainsPort(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(PORT_HEX)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Some builds hide proc networking from app UIDs; shell startup will still be attempted.
        }
        return false;
    }

    private static boolean isLocalPortReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 19_870), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeFlag() {
        try {
            File flag = new File(AUTOSTART_FLAG);
            File parent = flag.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(flag)) {
                out.write("1\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Flag write is best-effort.
        }
    }

    private static boolean tryStartWithSu() {
        return runDetached(new String[]{"su", "0", "sh", START_SCRIPT})
            || runDetached(new String[]{"su", "-c", "sh " + START_SCRIPT});
    }

    private static boolean tryStartWithSh() {
        // May run without shell inject privileges; still useful if OEMs allow it or su is present later.
        return runDetached(new String[]{"sh", START_SCRIPT})
            || runDetached(new String[]{"/system/bin/sh", START_SCRIPT});
    }

    private static boolean runDetached(String[] command) {
        try {
            final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            // Do not wait forever; boot path must stay short.
            Thread waiter = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "skyadb-start-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(3_000L);
            if (waiter.isAlive()) {
                return true;
            }
            return process.exitValue() == 0;
        } catch (Exception error) {
            Log.d(TAG, "start command failed: " + command[0] + " -> " + error.getMessage());
            return false;
        }
    }

    private static boolean runRootCommand(String[] command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            Thread waiter = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "skyadb-root-install-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(ROOT_COMMAND_TIMEOUT_MS);
            if (waiter.isAlive()) {
                process.destroy();
                Log.w(TAG, "root command timed out: " + command[0]);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception error) {
            Log.d(TAG, "root command failed: " + command[0] + " -> " + error.getMessage());
            return false;
        }
    }

    private static final String ROOT_SERVICE_SCRIPT_CONTENT = """
        #!/system/bin/sh
        DIR=/data/local/tmp/skyadb-lanmouse
        JAR=$DIR/skyadb-lanmouse-core.jar
        LOG=$DIR/skyadb-lanmouse.log
        PID=$DIR/skyadb-lanmouse.pid
        WATCHDOG_PID=$DIR/root-autostart.pid
        PORT_HEX=4D9E
        mkdir -p "$DIR"

        if [ -s "$WATCHDOG_PID" ] && kill -0 "$(cat "$WATCHDOG_PID")" >/dev/null 2>&1; then
          exit 0
        fi
        echo $$ > "$WATCHDOG_PID"
        trap '' HUP
        trap 'rm -f "$WATCHDOG_PID"; exit 0' INT TERM

        wait_boot=0
        while [ "$wait_boot" -lt 120 ]; do
          [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
          wait_boot=$((wait_boot + 1))
          sleep 1
        done

        is_listening() {
          grep -q ":$PORT_HEX" /proc/net/tcp /proc/net/tcp6 2>/dev/null && return 0
          netstat -an 2>/dev/null | grep -q "[:.]19870 .*LISTEN" && return 0
          return 1
        }

        start_core() {
          if [ ! -f "$JAR" ]; then
            echo "$(date '+%F %T') missing core jar" >> "$LOG"
            return
          fi
          is_listening && return
          if [ -s "$PID" ]; then
            kill "$(cat "$PID")" >/dev/null 2>&1 || true
          fi
          pkill -f "[c]om.server.skyadb.core.Main" >/dev/null 2>&1 || true
          CLASSPATH="$JAR" /system/bin/app_process / com.server.skyadb.core.Main >> "$LOG" 2>&1 </dev/null &
          echo $! > "$PID"
          echo "$(date '+%F %T') root service.d requested core start pid=$!" >> "$LOG"
        }

        while true; do
          start_core
          sleep 12
        done
        """;
}
