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

    InputController() throws Exception {
        screen = ScreenInfo.read();
        cursorX = screen.width / 2f;
        cursorY = screen.height / 2f;
        ManagerMethod managerMethod = findInputManager();
        inputManager = managerMethod.manager;
        injectInputEvent = managerMethod.method;
        setDisplayId = findSetDisplayId();
        cursorOverlay = new CursorOverlay(screen);
        injectHover();
    }

    synchronized ScreenInfo screenInfo() {
        screen = ScreenInfo.read();
        return screen;
    }

    synchronized int cursorX() {
        return Math.round(cursorX);
    }

    synchronized int cursorY() {
        return Math.round(cursorY);
    }

    synchronized boolean cursorVisible() {
        return cursorOverlay.isVisible();
    }

    synchronized String cursorRenderMode() {
        return cursorOverlay.renderMode();
    }

    synchronized int cursorDisplayId() {
        return screen.displayId;
    }

    synchronized int cursorLayerStack() {
        return screen.layerStack;
    }

    synchronized void moveCursor(float x, float y, boolean absolute) throws Exception {
        if (absolute) {
            cursorX = x;
            cursorY = y;
        } else {
            cursorX += x;
            cursorY += y;
        }
        cursorX = clamp(cursorX, 0, screen.width);
        cursorY = clamp(cursorY, 0, screen.height);
        cursorOverlay.moveCenter(cursorX, cursorY);
        injectHover();
    }

    synchronized void cursorTouchDown(float x, float y) {
        cursorOverlay.press();
    }

    synchronized void cursorTouchMove(float x, float y) {
        // Cursor movement is handled by moveCursor; this command only preserves press feedback.
    }

    synchronized void cursorTouchUp() {
        cursorOverlay.release();
    }

    synchronized void cursorTapAnimation() {
        cursorOverlay.press();
        cursorOverlay.release();
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
        if (touching) {
            injectTouch(MotionEvent.ACTION_CANCEL, touchX, touchY, touchDownTime);
        }
        touchStartX = clamp(x, 0, screen.width - 1);
        touchStartY = clamp(y, 0, screen.height - 1);
        touchX = touchStartX;
        touchY = touchStartY;
        touchDownTime = SystemClock.uptimeMillis();
        touching = true;
        cursorOverlay.press();
        injectTouch(MotionEvent.ACTION_DOWN, touchX, touchY, touchDownTime);
    }

    synchronized void touchMove(float dx, float dy, boolean accumulated) throws Exception {
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
    }

    synchronized void touchUp() throws Exception {
        if (!touching) {
            return;
        }
        injectTouch(MotionEvent.ACTION_UP, touchX, touchY, touchDownTime);
        touching = false;
        cursorOverlay.release();
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

    private void injectTapAtCursor(long durationMs) throws Exception {
        injectTouchSequence(cursorX, cursorY, durationMs);
        cursorOverlay.release();
        injectHover();
    }

    private void injectTouchSequence(float x, float y, long durationMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, x, y, downTime);
        cursorOverlay.press();
        SystemClock.sleep(durationMs);
        injectTouch(MotionEvent.ACTION_UP, x, y, downTime);
        cursorOverlay.release();
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
        cursorOverlay.close();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
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
