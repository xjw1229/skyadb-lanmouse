package com.server.skyadb.lanmouse;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import org.json.JSONObject;

public final class SkyAdbInputMethodService extends InputMethodService {
    private static volatile SkyAdbInputMethodService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LanMouseBridge bridge;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        bridge = new LanMouseBridge(this);
        bridge.start();
    }

    @Override
    public void onDestroy() {
        if (bridge != null) {
            bridge.stop();
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (bridge != null) {
            bridge.start();
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public View onCreateInputView() {
        TextView view = new TextView(this);
        int height = Math.round(48 * getResources().getDisplayMetrics().density);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        view.setGravity(Gravity.CENTER);
        view.setText("SkyADB 飞鼠输入中");
        view.setTextSize(16);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(0xCC202124);
        return view;
    }

    @Override
    public void onUpdateSelection(
        int oldSelStart,
        int oldSelEnd,
        int newSelStart,
        int newSelEnd,
        int candidatesStart,
        int candidatesEnd
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (bridge != null) {
            bridge.sendSelection(newSelStart, newSelEnd);
        }
    }

    static boolean ensureBridge() {
        SkyAdbInputMethodService service = instance;
        if (service == null || service.bridge == null) {
            return false;
        }
        service.bridge.start();
        return true;
    }

    void handleServerMessage(String message) {
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type", "");
                if ("imeInput".equals(type)) {
                    handleImeInput(json);
                } else if ("imeShow".equals(type)) {
                    if (!isInputViewShown()) {
                        requestShowSelf(0);
                    }
                } else if ("imeDismiss".equals(type)) {
                    if (isInputViewShown()) {
                        requestHideSelf(0);
                    }
                }
            } catch (Exception ignored) {
                // Ignore malformed or unrelated server messages.
            }
        });
    }

    private void handleImeInput(JSONObject json) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        String action = json.optString("action", "");
        switch (action) {
            case "commit":
                String text = json.optString("text", "");
                if (!TextUtils.isEmpty(text)) {
                    connection.commitText(text, 1);
                }
                break;
            case "delete":
                int count = Math.max(1, Math.min(json.optInt("count", 1), 100));
                if (!connection.deleteSurroundingText(count, 0)) {
                    for (int i = 0; i < count; i++) {
                        sendKey(connection, KeyEvent.KEYCODE_DEL);
                    }
                }
                break;
            case "clear":
                connection.performContextMenuAction(android.R.id.selectAll);
                connection.commitText("", 1);
                break;
            case "enter":
                sendKey(connection, KeyEvent.KEYCODE_ENTER);
                break;
            default:
                break;
        }
    }

    private static void sendKey(InputConnection connection, int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
