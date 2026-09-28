package com.server.skyadb.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Injects touch and key events while maintaining the visible SurfaceControl cursor. */
final class InputController implements AutoCloseable {
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final int DISPLAY_ID = 0;
    private static final int SOURCE_MOUSE = 8194;
    private static final int SOURCE_TOUCHSCREEN = 4098;

    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method setDisplayId;
    private final CursorOverlay cursorOverlay;

    private ScreenInfo screen;
    private float cursorX;
    private float cursorY;
    private boolean touching;
    private long touchDownTime;
    private float touchStartX;
    private float touchStartY;
    private float touchX;
    private float touchY;

    /** Display size is re-read at most this often so rotation cannot freeze the reachable area. */
    private static final long SCREEN_REFRESH_INTERVAL_MS = 400L;
    private long lastScreenRefresh;

    InputController() throws Exception {
        screen = ScreenInfo.read();
        cursorX = screen.width / 2f;
        cursorY = screen.height / 2f;
        ManagerMethod managerMethod = findInputManager();
        inputManager = managerMethod.manager;
        injectInputEvent = managerMethod.method;
        setDisplayId = findSetDisplayId();
        cursorOverlay = createCursorOverlay(screen);
        injectHover();
    }

    /** Cursor drawing is optional: a box without a usable SurfaceControl still gets input injection. */
    private static CursorOverlay createCursorOverlay(ScreenInfo screen) {
        try {
            return new CursorOverlay(screen);
        } catch (Throwable error) {
            System.err.println("SkyADB cursor overlay unavailable, continuing without it: " + error);
            return null;
        }
    }

    synchronized ScreenInfo screenInfo() {
        screen = ScreenInfo.read();
        applyScreenBounds();
        return screen;
    }

    /**
     * Keeps the cursor coordinate space aligned with the live display.
     *
     * <p>The display can rotate or resize at any time (phone turned sideways, split screen, an
     * override applied by {@code wm size}). Caching the size once made every later cursor move and
     * touch clamp into the old portrait rectangle, which looked like "only a small corner is
     * controllable" after the target rotated.
     */
    private void refreshScreenIfStale() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScreenRefresh < SCREEN_REFRESH_INTERVAL_MS) {
            return;
        }
        lastScreenRefresh = now;
        ScreenInfo latest = ScreenInfo.read();
        screen = latest;
        applyScreenBounds();
    }

    private void applyScreenBounds() {
        if (cursorOverlay != null) {
            cursorOverlay.updateBounds(screen.width, screen.height);
        }
        cursorX = clamp(cursorX, 0, screen.width);
        cursorY = clamp(cursorY, 0, screen.height);
    }

    synchronized int cursorX() {
        return Math.round(cursorX);
    }

    synchronized int cursorY() {
        return Math.round(cursorY);
    }

    synchronized boolean cursorVisible() {
        return cursorOverlay != null && cursorOverlay.isVisible();
    }

    synchronized String cursorRenderMode() {
        return cursorOverlay == null ? "none" : cursorOverlay.renderMode();
    }

    synchronized int cursorDisplayId() {
        return screen.displayId;
    }

    synchronized int cursorLayerStack() {
        return screen.layerStack;
    }

    synchronized void moveCursor(float x, float y, boolean absolute) throws Exception {
        refreshScreenIfStale();
        if (absolute) {
            cursorX = x;
            cursorY = y;
        } else {
            cursorX += x;
            cursorY += y;
        }
        cursorX = clamp(cursorX, 0, screen.width);
        cursorY = clamp(cursorY, 0, screen.height);
        if (cursorOverlay != null) {
            cursorOverlay.moveCenter(cursorX, cursorY);
        }
        injectHover();
    }

    synchronized void cursorTouchDown(float x, float y) {
        if (cursorOverlay != null) {
            cursorOverlay.press();
        }
    }

    synchronized void cursorTouchMove(float x, float y) {
        // Cursor movement is handled by moveCursor; this command only preserves press feedback.
    }

    synchronized void cursorTouchUp() {
        if (cursorOverlay != null) {
            cursorOverlay.release();
        }
    }

    synchronized void cursorTapAnimation() {
        if (cursorOverlay != null) {
            cursorOverlay.press();
            cursorOverlay.release();
        }
    }

    synchronized void tap(long durationMs) throws Exception {
        injectTapAtCursor(Math.max(40, Math.min(durationMs, 1000)));
    }

    synchronized void longPress(long durationMs) throws Exception {
        injectTapAtCursor(Math.max(300, Math.min(durationMs, 3000)));
    }

    synchronized void tapAt(float x, float y, long durationMs) throws Exception {
        injectTouchSequence(x, y, Math.max(40, Math.min(durationMs, 1000)));
    }

    synchronized void longPressAt(float x, float y, long durationMs) throws Exception {
        injectTouchSequence(x, y, Math.max(300, Math.min(durationMs, 3000)));
    }

    synchronized void touchDown(float x, float y) throws Exception {
        refreshScreenIfStale();
        if (touching) {
            injectTouch(MotionEvent.ACTION_CANCEL, touchX, touchY, touchDownTime);
        }
        touchStartX = clamp(x, 0, screen.width - 1);
        touchStartY = clamp(y, 0, screen.height - 1);
        touchX = touchStartX;
        touchY = touchStartY;
        touchDownTime = SystemClock.uptimeMillis();
        touching = true;
        if (cursorOverlay != null) {
            cursorOverlay.press();
        }
        injectTouch(MotionEvent.ACTION_DOWN, touchX, touchY, touchDownTime);
    }

    /** Presses at a position expressed as a fraction of the current display. */
    synchronized void touchDownAtRatio(double ratioX, double ratioY) throws Exception {
        refreshScreenIfStale();
        float x = (float) (clamp01(ratioX) * (screen.width - 1));
        float y = (float) (clamp01(ratioY) * (screen.height - 1));
        touchDown(x, y);
    }

    synchronized void touchMove(float dx, float dy, boolean accumulated) throws Exception {
        refreshScreenIfStale();
        if (!touching) {
            touchDown(cursorX, cursorY);
        }
        float nextX = accumulated ? touchStartX + dx : touchX + dx;
        float nextY = accumulated ? touchStartY + dy : touchY + dy;
        nextX = clamp(nextX, 0, screen.width - 1);
        nextY = clamp(nextY, 0, screen.height - 1);

        injectTouch(MotionEvent.ACTION_MOVE, nextX, nextY, touchDownTime);
        touchX = nextX;
        touchY = nextY;
        // A press-and-drag moves the finger, so the visible pointer must travel with it and the
        // next gesture must start from where this one ended (mouse-like absolute pointer).
        cursorX = nextX;
        cursorY = nextY;
        if (cursorOverlay != null) {
            cursorOverlay.moveCenter(cursorX, cursorY);
        }
    }

    synchronized void touchUp() throws Exception {
        if (!touching) {
            return;
        }
        injectTouch(MotionEvent.ACTION_UP, touchX, touchY, touchDownTime);
        touching = false;
        if (cursorOverlay != null) {
            cursorOverlay.release();
        }
        injectHover();
    }

    synchronized void keyEvent(String keyName) throws Exception {
        int keyCode = KeyEvent.keyCodeFromString(keyName);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            throw new IllegalArgumentException("Unknown key: " + keyName);
        }
        injectKeyCode(keyCode);
    }

    private void injectKeyCode(int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM;
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD));
        inject(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD));
    }

    synchronized void injectText(String text) throws Exception {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }
        ClipboardManager clipboard = (ClipboardManager) ShellContext.get()
            .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            throw new IllegalStateException("Clipboard service is unavailable");
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("skyadb", text));
        SystemClock.sleep(50L);
        injectKeyCode(KeyEvent.KEYCODE_PASTE);
        System.out.println("SkyADB clipboard paste injected, textLength=" + text.length());
    }

    /**
     * Types text with virtual-keyboard key events so the focused editor receives it even when the
     * TV or box has no usable input method installed. Returns false when the text contains
     * characters a virtual keyboard cannot produce, so the caller can fall back to clipboard paste.
     */
    synchronized boolean injectTypedText(String text) throws Exception {
        if (text == null || text.isEmpty()) {
            return false;
        }
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray());
        if (events == null || events.length == 0) {
            return false;
        }
        for (KeyEvent event : events) {
            injectTypedKeyEvent(event);
        }
        return true;
    }

    private void injectTypedKeyEvent(KeyEvent source) throws Exception {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(
            now,
            now,
            source.getAction(),
            source.getKeyCode(),
            source.getRepeatCount(),
            source.getMetaState(),
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            source.getScanCode(),
            source.getFlags() | KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD
        ));
    }

    private void injectTapAtCursor(long durationMs) throws Exception {
        injectTouchSequence(cursorX, cursorY, durationMs);
        if (cursorOverlay != null) {
            cursorOverlay.release();
        }
        injectHover();
    }

    private void injectTouchSequence(float x, float y, long durationMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, x, y, downTime);
        if (cursorOverlay != null) {
            cursorOverlay.press();
        }
        SystemClock.sleep(durationMs);
        injectTouch(MotionEvent.ACTION_UP, x, y, downTime);
        if (cursorOverlay != null) {
            cursorOverlay.release();
        }
        injectHover();
    }

    private void injectTouch(int action, float x, float y, long downTime) throws Exception {
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        coordinates.pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0f : 1f;
        coordinates.size = 1f;
        MotionEvent event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            new MotionEvent.PointerProperties[] {properties},
            new MotionEvent.PointerCoords[] {coordinates},
            0,
            0,
            1f,
            1f,
            0,
            0,
            SOURCE_TOUCHSCREEN,
            0
        );
        inject(event);
    }

    private void injectHover() throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = cursorX;
        coordinates.y = cursorY;
        coordinates.pressure = 0f;
        coordinates.size = 1f;
        MotionEvent event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_HOVER_MOVE,
            1,
            new MotionEvent.PointerProperties[] {properties},
            new MotionEvent.PointerCoords[] {coordinates},
            0,
            0,
            1f,
            1f,
            0,
            0,
            SOURCE_MOUSE,
            0
        );
        inject(event);
    }

    private void inject(InputEvent event) throws Exception {
        try {
            if (setDisplayId != null) {
                setDisplayId.invoke(event, DISPLAY_ID);
            }
            Object result = injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
            if (result instanceof Boolean && !((Boolean) result)) {
                throw new IllegalStateException("Android rejected the injected input event");
            }
        } finally {
            if (event instanceof MotionEvent) {
                ((MotionEvent) event).recycle();
            }
        }
    }

    private static ManagerMethod findInputManager() throws Exception {
        Throwable lastError = null;
        Context context = SystemContext.get();
        if (context != null) {
            try {
                Object manager = context.getSystemService(Context.INPUT_SERVICE);
                if (manager != null) {
                    Method method = findInjectMethod(manager.getClass());
                    if (method != null) {
                        return new ManagerMethod(manager, method);
                    }
                }
            } catch (Throwable error) {
                lastError = error;
            }
        }
        for (String className : new String[] {
            "android.hardware.input.InputManager",
            "android.hardware.input.InputManagerGlobal"
        }) {
            try {
                Class<?> type = Class.forName(className);
                Method getInstance = type.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object manager = getInstance.invoke(null);
                Method method = findInjectMethod(type);
                if (method != null) {
                    return new ManagerMethod(manager, method);
                }
            } catch (Throwable error) {
                lastError = error;
            }
        }
        throw new IllegalStateException("No Android input injection API is available", lastError);
    }

    private static Method findInjectMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("injectInputEvent")
                && parameters.length == 2
                && InputEvent.class.isAssignableFrom(parameters[0])
                && parameters[1] == int.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findSetDisplayId() {
        try {
            Method method = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (cursorOverlay != null) {
            cursorOverlay.close();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private static final class ManagerMethod {
        final Object manager;
        final Method method;

        ManagerMethod(Object manager, Method method) {
            this.manager = manager;
            this.method = method;
        }
    }
}
