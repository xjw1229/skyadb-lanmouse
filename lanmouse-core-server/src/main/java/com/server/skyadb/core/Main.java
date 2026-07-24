package com.server.skyadb.core;

import android.os.Looper;
import java.lang.reflect.Field;

/** Entry point launched by app_process under the ADB shell user. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("SkyADB core uncaught error on " + thread.getName());
            error.printStackTrace(System.err);
        });

        try {
            prepareMainLooper();
            InputController inputController = new InputController();
            LanMouseServer server = new LanMouseServer(19_870, inputController);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "skyadb-core-shutdown"));
            System.out.println("SkyADB LAN mouse core listening on 0.0.0.0:19870");
            server.serveForever();
        } catch (Throwable error) {
            System.err.println("SkyADB LAN mouse core failed to start");
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void prepareMainLooper() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        if (mainLooper.get(null) == null) {
            mainLooper.set(null, Looper.myLooper());
        }
        System.out.println("SkyADB main looper initialized");
    }
}
