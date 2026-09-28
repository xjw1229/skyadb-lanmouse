package com.server.skyadb.lanmouse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the SkyADB core after TV boot when one-click deploy left autostart enabled.
 * Prefer shell/su when available so inject privileges remain intact.
 */
public final class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "SkyADB-Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_USER_UNLOCKED.equals(action)
            && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
            && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "boot event: " + action);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // Restore original IME first so remote control typing works even if core is down.
                ImeRestoreHelper.restorePreviousIme();
                Context appContext = context.getApplicationContext();
                CoreAutostartJobService.scheduleNow(appContext, action);
                CoreAutostart.startIfEnabled(appContext);
            } catch (Throwable error) {
                Log.w(TAG, "boot autostart failed: " + error.getMessage(), error);
            } finally {
                pending.finish();
            }
        }, "skyadb-boot").start();
    }
}
