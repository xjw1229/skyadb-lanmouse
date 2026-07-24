package com.server.skyadb.core;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import java.lang.reflect.Field;

/** Presents app_process services as the ADB shell package. */
final class ShellContext extends ContextWrapper {
    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int SHELL_UID = 2000;
    private static final ShellContext INSTANCE = create();

    private ShellContext(Context base) {
        super(base);
    }

    static ShellContext get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("Shell context is unavailable");
        }
        return INSTANCE;
    }

    private static ShellContext create() {
        Context context = SystemContext.get();
        if (context == null) {
            System.err.println("SkyADB shell context unavailable: no system context");
            return null;
        }
        System.out.println("SkyADB shell context initialized as " + PACKAGE_NAME);
        return new ShellContext(context);
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    @TargetApi(31)
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(SHELL_UID)
            .setPackageName(PACKAGE_NAME)
            .build();
    }

    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service != null && (Context.CLIPBOARD_SERVICE.equals(name) || "semclipboard".equals(name))) {
            replaceServiceContext(service);
        }
        return service;
    }

    private void replaceServiceContext(Object service) {
        Class<?> type = service.getClass();
        while (type != null) {
            try {
                Field contextField = type.getDeclaredField("mContext");
                contextField.setAccessible(true);
                contextField.set(service, this);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to apply shell clipboard context", error);
            }
        }
        throw new IllegalStateException("Clipboard service has no mContext field");
    }
}
