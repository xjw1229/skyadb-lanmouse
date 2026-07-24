package com.server.skyadb.core;

import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScreenInfo {
    private static final Pattern WM_SIZE = Pattern.compile("(?:Override|Physical) size:\\s*(\\d+)x(\\d+)");

    final int width;
    final int height;
    final int displayId;
    final int layerStack;
    final int densityDpi;
    final float densityScale;

    ScreenInfo(int width, int height) {
        this(width, height, 0, 0, 160);
    }

    ScreenInfo(int width, int height, int displayId, int layerStack, int densityDpi) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.displayId = displayId;
        this.layerStack = layerStack;
        this.densityDpi = densityDpi > 0 ? densityDpi : 160;
        this.densityScale = this.densityDpi / 160f;
    }

    static ScreenInfo read() {
        ScreenInfo reflected = readFromDisplayManager();
        if (reflected != null) {
            return reflected;
        }
        ScreenInfo shell = readFromWmCommand();
        return shell != null ? shell : new ScreenInfo(1920, 1080);
    }

    private static ScreenInfo readFromDisplayManager() {
        try {
            Class<?> managerClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
            try {
                Method getDisplayInfo = managerClass.getDeclaredMethod("getDisplayInfo", int.class);
                getDisplayInfo.setAccessible(true);
                Object info = getDisplayInfo.invoke(manager, Display.DEFAULT_DISPLAY);
                if (info != null) {
                    Class<?> type = info.getClass();
                    int width = intField(type, info, "logicalWidth", 0);
                    int height = intField(type, info, "logicalHeight", 0);
                    if (width > 0 && height > 0) {
                        return new ScreenInfo(
                            width,
                            height,
                            Display.DEFAULT_DISPLAY,
                            intField(type, info, "layerStack", Display.DEFAULT_DISPLAY),
                            intField(type, info, "logicalDensityDpi", 160)
                        );
                    }
                }
            } catch (Throwable ignored) {
                // Older systems expose only getRealDisplay().
            }
            Method getRealDisplay = managerClass.getDeclaredMethod("getRealDisplay", int.class);
            getRealDisplay.setAccessible(true);
            Display display = (Display) getRealDisplay.invoke(manager, Display.DEFAULT_DISPLAY);
            if (display == null) {
                return null;
            }
            Point size = new Point();
            display.getRealSize(size);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return size.x > 0 && size.y > 0
                ? new ScreenInfo(size.x, size.y, Display.DEFAULT_DISPLAY, Display.DEFAULT_DISPLAY, metrics.densityDpi)
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intField(Class<?> type, Object object, String name, int fallback) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(object);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static ScreenInfo readFromWmCommand() {
        try {
            Process process = new ProcessBuilder("/system/bin/wm", "size").redirectErrorStream(true).start();
            ScreenInfo result = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = WM_SIZE.matcher(line);
                    if (matcher.find()) {
                        result = new ScreenInfo(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                        );
                    }
                }
            }
            process.waitFor();
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
