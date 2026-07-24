package com.server.skyadb.core;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Surface;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Transparent SurfaceControl overlay for the visible circular cursor. */
final class CursorOverlay implements AutoCloseable {
    private static final int CURSOR_SIZE_DP = 24;
    private static final int BORDER_WIDTH_DP = 3;
    private static final int CURSOR_COLOR = -7643914;
    private static final long AUTO_HIDE_MS = 5_000;

    private final SurfaceControl surfaceControl;
    private final Surface surface;
    private final int size;
    private final int halfSize;
    private final int screenWidth;
    private final int screenHeight;
    private final int layerStack;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> hideTask;
    private int left;
    private int top;
    private boolean visible;
    private float scale = 1f;

    CursorOverlay(ScreenInfo screen) throws Exception {
        size = Math.max(CURSOR_SIZE_DP, Math.round(CURSOR_SIZE_DP * screen.densityScale));
        halfSize = size / 2;
        screenWidth = screen.width;
        screenHeight = screen.height;
        layerStack = screen.layerStack;
        left = (screenWidth / 2) - halfSize;
        top = (screenHeight / 2) - halfSize;

        float stroke = Math.max(BORDER_WIDTH_DP, BORDER_WIDTH_DP * screen.densityScale);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setColor(CURSOR_COLOR);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(CURSOR_COLOR);

        surfaceControl = new SurfaceControl.Builder()
            .setName("SkyAdbCursor")
            .setBufferSize(size, size)
            .setFormat(PixelFormat.TRANSLUCENT)
            .build();
        surface = new Surface(surfaceControl);

        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        transaction.setLayer(surfaceControl, Integer.MAX_VALUE);
        transaction.setPosition(surfaceControl, left, top);
        transaction.setAlpha(surfaceControl, 1f);
        invokeOptional(transaction, "setLayerStack", new Class<?>[] {SurfaceControl.class, int.class}, surfaceControl, layerStack);
        if (!invokeOptional(transaction, "setTrustedOverlay", new Class<?>[] {SurfaceControl.class, boolean.class}, surfaceControl, true)) {
            invokeOptional(transaction, "setTrustedOverlay", new Class<?>[] {SurfaceControl.class, int.class}, surfaceControl, 1);
        }
        if (!invokeOptional(transaction, "show", new Class<?>[] {SurfaceControl.class}, surfaceControl)) {
            transaction.setVisibility(surfaceControl, true);
        }
        transaction.apply();
        transaction.close();

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "skyadb-cursor-timer");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        visible = true;
        redraw();
        scheduleHide();
        System.out.println("SkyADB cursor overlay created: " + size + "px, layerStack=" + layerStack);
    }

    synchronized int centerX() {
        return left + halfSize;
    }

    synchronized int centerY() {
        return top + halfSize;
    }

    synchronized boolean isVisible() {
        return visible;
    }

    synchronized String renderMode() {
        return "SurfaceControl";
    }

    synchronized void moveCenter(float centerX, float centerY) {
        int min = -halfSize;
        left = clamp(Math.round(centerX) - halfSize, min, screenWidth - halfSize);
        top = clamp(Math.round(centerY) - halfSize, min, screenHeight - halfSize);
        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        transaction.setPosition(surfaceControl, left, top);
        if (!visible) {
            transaction.setAlpha(surfaceControl, 1f);
            if (!invokeOptional(transaction, "show", new Class<?>[] {SurfaceControl.class}, surfaceControl)) {
                transaction.setVisibility(surfaceControl, true);
            }
            visible = true;
        }
        transaction.apply();
        transaction.close();
        scheduleHide();
    }

    synchronized void press() {
        scale = 0.9f;
        showNow();
        redraw();
    }

    synchronized void release() {
        scale = 1f;
        redraw();
        scheduleHide();
    }

    private synchronized void showNow() {
        if (hideTask != null) {
            hideTask.cancel(false);
            hideTask = null;
        }
        if (!visible) {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setAlpha(surfaceControl, 1f);
            if (!invokeOptional(transaction, "show", new Class<?>[] {SurfaceControl.class}, surfaceControl)) {
                transaction.setVisibility(surfaceControl, true);
            }
            transaction.apply();
            transaction.close();
            visible = true;
        }
    }

    private synchronized void scheduleHide() {
        showNow();
        hideTask = scheduler.schedule(this::hide, AUTO_HIDE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void hide() {
        if (!visible) {
            return;
        }
        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        transaction.setAlpha(surfaceControl, 0f);
        transaction.apply();
        transaction.close();
        visible = false;
        hideTask = null;
    }

    private synchronized void redraw() {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            float center = size / 2f;
            if (scale != 1f) {
                canvas.save();
                canvas.scale(scale, scale, center, center);
            }
            float outerRadius = (size - ringPaint.getStrokeWidth()) / 2f;
            canvas.drawCircle(center, center, outerRadius, ringPaint);
            canvas.drawCircle(center, center, outerRadius / 3f, dotPaint);
            if (scale != 1f) {
                canvas.restore();
            }
        } finally {
            if (canvas != null) {
                surface.unlockCanvasAndPost(canvas);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (hideTask != null) {
            hideTask.cancel(false);
            hideTask = null;
        }
        scheduler.shutdownNow();
        try {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            if (!invokeOptional(transaction, "hide", new Class<?>[] {SurfaceControl.class}, surfaceControl)) {
                transaction.setVisibility(surfaceControl, false);
            }
            transaction.apply();
            transaction.close();
        } catch (Throwable ignored) {
            // Process shutdown will release any remaining native resources.
        }
        surface.release();
        surfaceControl.release();
        visible = false;
    }

    private static boolean invokeOptional(
        SurfaceControl.Transaction transaction,
        String name,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        try {
            Method method = SurfaceControl.Transaction.class.getMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(transaction, arguments);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
