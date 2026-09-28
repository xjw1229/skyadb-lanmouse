package com.server.skyadb.lanmouse;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Restores the TV's previous default IME after temporary SkyADB IME use. */
final class ImeRestoreHelper {
    private static final String TAG = "SkyADB-ImeRestore";
    private static final String CURSOR_IME = "com.server.skyadb.lanmouse/.SkyAdbInputMethodService";
    private static final String PREVIOUS_IME_FILE = "/data/local/tmp/skyadb-lanmouse/previous-ime.txt";

    private ImeRestoreHelper() {}

    static void restorePreviousIme() {
        String previous = readPreviousIme();
        String current = runCapture("settings", "get", "secure", "default_input_method");
        if (previous != null
            && !previous.isEmpty()
            && !"null".equals(previous)
            && !CURSOR_IME.equals(previous)) {
            if (runImeSet(previous)) {
                Log.i(TAG, "restored previous IME: " + previous);
                return;
            }
        }
        if (current == null || !CURSOR_IME.equals(current.trim())) {
            return;
        }
        String fallback = findFallbackIme();
        if (fallback != null && runImeSet(fallback)) {
            Log.i(TAG, "restored fallback IME: " + fallback);
        }
    }

    private static boolean runImeSet(String imeId) {
        try {
            Process process = new ProcessBuilder("/system/bin/ime", "set", imeId)
                .redirectErrorStream(true)
                .start();
            // Drain.
            while (process.getInputStream().read() != -1) {
            }
            return process.waitFor() == 0;
        } catch (Exception error) {
            Log.w(TAG, "ime set failed: " + error.getMessage());
            return false;
        }
    }

    private static String findFallbackIme() {
        String listed = runCapture("ime", "list", "-s");
        if (listed == null || listed.isEmpty()) {
            return null;
        }
        String[] lines = listed.split("\\r?\\n");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && !CURSOR_IME.equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String readPreviousIme() {
        File file = new File(PREVIOUS_IME_FILE);
        if (!file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
        )) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception error) {
            return null;
        }
    }

    private static String runCapture(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            process.waitFor();
            return output.toString().trim();
        } catch (Exception error) {
            return null;
        }
    }
}
