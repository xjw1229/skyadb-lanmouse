package com.server.skyadb.lanmouse;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ServerReadyProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("ensureReady".equals(method)) {
            result.putBoolean("ready", SkyAdbInputMethodService.ensureBridge());
        } else if ("enableAutostart".equals(method)) {
            if (getContext() != null) {
                CoreAutostart.enable(getContext());
                CoreAutostartJobService.scheduleNow(getContext(), "provider-enable");
            }
            result.putBoolean("enabled", true);
        } else if ("installRootAutostart".equals(method)) {
            boolean rootInstalled = CoreAutostart.installRootServiceIfAvailable();
            result.putBoolean("rootInstalled", rootInstalled);
        } else if ("startCore".equals(method)) {
            if (getContext() != null) {
                boolean ready = CoreAutostart.startIfEnabled(getContext());
                if (!ready) {
                    CoreAutostartJobService.scheduleRetry(getContext(), "provider-start");
                }
                result.putBoolean("ready", ready);
            }
            result.putBoolean("started", true);
        } else if ("restoreIme".equals(method)) {
            ImeRestoreHelper.restorePreviousIme();
            result.putBoolean("restored", true);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
