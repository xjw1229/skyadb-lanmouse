package com.server.skyadb.lanmouse;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

final class LanMouseBridge {
    private static final String ENDPOINT = "ws://127.0.0.1:19870";

    private final SkyAdbInputMethodService service;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean active;
    private volatile boolean connecting;
    private volatile WebSocket socket;

    LanMouseBridge(SkyAdbInputMethodService service) {
        this.service = service;
    }

    synchronized void start() {
        active = true;
        if (socket != null || connecting) {
            return;
        }
        connecting = true;
        Request request = new Request.Builder().url(ENDPOINT).build();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connecting = false;
                if (!active) {
                    webSocket.close(1000, "service stopped");
                    return;
                }
                socket = webSocket;
                webSocket.send("{\"type\":\"cursorRegister\"}");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                service.handleServerMessage(text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                clearAndReconnect(webSocket);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                clearAndReconnect(webSocket);
            }
        });
    }

    void sendSelection(int start, int end) {
        WebSocket current = socket;
        if (current == null) {
            return;
        }
        try {
            current.send(
                new JSONObject()
                    .put("type", "imeCursor")
                    .put("selectionStart", start)
                    .put("selectionEnd", end)
                    .toString()
            );
        } catch (Exception ignored) {
            // Selection updates are optional and must not interrupt text input.
        }
    }

    synchronized void stop() {
        active = false;
        connecting = false;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.close(1000, "input method stopped");
            current.cancel();
        }
        reconnectExecutor.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private synchronized void clearAndReconnect(WebSocket webSocket) {
        if (socket == webSocket) {
            socket = null;
        }
        connecting = false;
        if (active && !reconnectExecutor.isShutdown()) {
            reconnectExecutor.schedule(this::start, 1, TimeUnit.SECONDS);
        }
    }
}
