package com.server.skyadb.core;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Creates the minimal Android system context expected by services launched through app_process. */
final class SystemContext {
    private static final Context INSTANCE = create();

    private SystemContext() {}

    static Context get() {
        return INSTANCE;
    }

    private static Context create() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = null;
            try {
                Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
                current.setAccessible(true);
                activityThread = current.invoke(null);
            } catch (Throwable ignored) {
                // app_process normally starts without an ActivityThread instance.
            }
            if (activityThread == null) {
                Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                activityThread = constructor.newInstance();

                Field currentThread = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                currentThread.setAccessible(true);
                currentThread.set(null, activityThread);

                Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
                systemThread.setAccessible(true);
                systemThread.setBoolean(activityThread, true);
            }

            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context context = (Context) getSystemContext.invoke(activityThread);
            if (context != null) {
                System.out.println("SkyADB system context initialized");
            }
            return context;
        } catch (Throwable error) {
            System.err.println("SkyADB system context unavailable: " + error);
            return null;
        }
    }
}
