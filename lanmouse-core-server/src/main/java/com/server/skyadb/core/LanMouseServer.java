package com.server.skyadb.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class LanMouseServer implements WebSocketConnection.Listener, AutoCloseable {
    private static final String INPUT_METHOD = "com.server.skyadb.lanmouse/.SkyAdbInputMethodService";
    private static final String PREVIOUS_IME_FILE = "/data/local/tmp/skyadb-lanmouse/previous-ime.txt";

    private final int port;
    private final InputController input;
    private final Object inputMethodLock = new Object();
    private final Set<WebSocketConnection> clients = Collections.newSetFromMap(
        new ConcurrentHashMap<WebSocketConnection, Boolean>()
    );
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();

    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private volatile WebSocketConnection inputMethodClient;
    private volatile String previousIme;
    private volatile boolean skyAdbImeActive;
    private volatile int imeAvailability = IME_UNKNOWN;

    LanMouseServer(int port, InputController input) {
        this.port = port;
        this.input = input;
        this.previousIme = readPreviousImeFile();
    }

    void serveForever() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("0.0.0.0", port));
        serverSocket = listener;
        while (running) {
            try {
                Socket socket = listener.accept();
                clientExecutor.execute(new WebSocketConnection(socket, this));
            } catch (IOException error) {
                if (running) {
                    throw error;
                }
            }
        }
    }

    @Override
    public void onOpen(WebSocketConnection connection) {
        clients.add(connection);
    }

    @Override
    public void onText(WebSocketConnection connection, String message) {
        String type = "unknown";
        try {
            JSONObject json = new JSONObject(message);
            type = json.optString("type", "");
            switch (type) {
                case "cursorRegister":
                    synchronized (inputMethodLock) {
                        inputMethodClient = connection;
                        inputMethodLock.notifyAll();
                    }
                    System.out.println("SkyADB input method bridge connected");
                    sendOk(connection, type);
                    return;
                case "cursorInfo":
                    sendCursorInfo(connection);
                    return;
                case "moveCursor":
                case "move":
                    input.moveCursor(
                        (float) json.optDouble("x", 0),
                        (float) json.optDouble("y", 0),
                        json.has("absolute")
                            ? json.optBoolean("absolute")
                            : (json.has("abs") ? json.optBoolean("abs") : true)
                    );
                    break;
                case "cursorTouchDown":
                    input.cursorTouchDown(
                        (float) json.optDouble("x", 0),
                        (float) json.optDouble("y", 0)
                    );
                    break;
                case "cursorTouchMove":
                    input.cursorTouchMove(
                        (float) json.optDouble("x", 0),
                        (float) json.optDouble("y", 0)
                    );
                    break;
                case "cursorTouchUp":
                    input.cursorTouchUp();
                    break;
                case "cursorTapAnimation":
                    input.cursorTapAnimation();
                    break;
                case "tapHere":
                    input.tap(json.optLong("duration", 100));
                    break;
                case "tap":
                    requireCoordinates(json, type);
                    input.tapAt(
                        (float) json.optDouble("x"),
                        (float) json.optDouble("y"),
                        json.optLong("duration", 100)
                    );
                    break;
                case "longPressHere":
                    input.longPress(json.optLong("duration", 650));
                    break;
                case "longPress":
                    requireCoordinates(json, type);
                    input.longPressAt(
                        (float) json.optDouble("x"),
                        (float) json.optDouble("y"),
                        json.optLong("duration", 500)
                    );
                    break;
                case "keyEvent":
                    input.keyEvent(json.optString("event", ""));
                    break;
                case "touchDown":
                    input.touchDown(
                        json.has("x") ? (float) json.optDouble("x") : input.cursorX(),
                        json.has("y") ? (float) json.optDouble("y") : input.cursorY()
                    );
                    break;
                case "touchDownRatio":
                    // Ratios are resolved against the live display, so a rotated or resized target
                    // cannot leave the anchor computed from a stale client-side size.
                    input.touchDownAtRatio(
                        json.optDouble("rx", 0.5),
                        json.optDouble("ry", 0.5)
                    );
                    break;
                case "touchMove":
                    input.touchMove(
                        (float) json.optDouble("dx", 0),
                        (float) json.optDouble("dy", 0),
                        json.optBoolean("accumulated", true)
                    );
                    break;
                case "touchUp":
                    input.touchUp();
                    break;
                case "switchIme":
                    switchInputMethod();
                    break;
                case "restoreIme":
                    restorePreviousInputMethod();
                    break;
                case "inputText":
                    input.injectText(json.optString("text", ""));
                    break;
                case "imeInput":
                    deliverTextInput(json, false);
                    break;
                case "imeInputDone":
                    deliverTextInput(json, true);
                    break;
                case "imeDismiss":
                    dismissInputMethodView();
                    // Always leave the original IME as default after temporary use.
                    restorePreviousInputMethod();
                    break;
                case "imeCursor":
                    return;
                default:
                    throw new IllegalArgumentException("Unknown command: " + type);
            }
            sendOk(connection, type);
        } catch (Throwable error) {
            sendError(connection, type, error.getMessage());
            System.err.println("SkyADB command failed [" + type + "]: " + error.getMessage());
        }
    }

    @Override
    public void onClose(WebSocketConnection connection) {
        clients.remove(connection);
        if (inputMethodClient == connection) {
            synchronized (inputMethodLock) {
                if (inputMethodClient == connection) {
                    inputMethodClient = null;
                    System.out.println("SkyADB input method bridge disconnected");
                }
                inputMethodLock.notifyAll();
            }
        }
        // When the last phone client leaves, restore the TV default IME.
        if (countPhoneClients() == 0 && skyAdbImeActive) {
            try {
                restorePreviousInputMethod();
            } catch (Throwable error) {
                System.err.println("SkyADB failed to restore previous IME: " + error.getMessage());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (skyAdbImeActive) {
            try {
                restorePreviousInputMethod();
            } catch (Throwable ignored) {
                // Best effort on shutdown.
            }
        }
        ServerSocket listener = serverSocket;
        serverSocket = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // The accept loop is already stopping.
            }
        }
        for (WebSocketConnection client : clients) {
            client.close();
        }
        clients.clear();
        clientExecutor.shutdownNow();
        input.close();
    }

    private int countPhoneClients() {
        int count = 0;
        for (WebSocketConnection client : clients) {
            if (client != inputMethodClient) {
                count++;
            }
        }
        return count;
    }

    private void sendCursorInfo(WebSocketConnection connection) throws Exception {
        ScreenInfo screen = input.screenInfo();
        JSONObject data = new JSONObject()
            .put("centerX", input.cursorX())
            .put("centerY", input.cursorY())
            .put("visible", input.cursorVisible())
            .put("screenWidth", screen.width)
            .put("screenHeight", screen.height)
            .put("displayId", input.cursorDisplayId())
            .put("layerStack", input.cursorLayerStack())
            .put("renderMode", input.cursorRenderMode());
        JSONObject response = new JSONObject()
            .put("ok", true)
            .put("type", "cursorInfo")
            .put("data", data);
        connection.sendText(response.toString());
    }

    /**
     * Text input entry point that keeps working on TVs and old boxes with no usable input method.
     *
     * <p>When {@code com.server.skyadb.lanmouse} is an installed and enabled input method the text
     * goes through the IME bridge as before. Otherwise the text is injected straight into the
     * focused editor with key events (and clipboard paste for characters no virtual keyboard can
     * produce), so a box without any IME picker can still be typed into.
     */
    private void deliverTextInput(JSONObject json, boolean restoreAfterwards) throws Exception {
        if (isInputMethodAvailable()) {
            WebSocketConnection client = inputMethodClient;
            if (client == null) {
                switchInputMethod();
                client = inputMethodClient;
            }
            if (client != null) {
                client.sendText(new JSONObject(json.toString()).put("type", "imeInput").toString());
                if (restoreAfterwards) {
                    Thread.sleep(INPUT_RESTORE_DELAY_MS);
                    dismissInputMethodView();
                    restorePreviousInputMethod();
                }
                return;
            }
        }
        injectTextWithoutInputMethod(json);
        if (restoreAfterwards && skyAdbImeActive) {
            dismissInputMethodView();
            restorePreviousInputMethod();
        }
    }

    private void injectTextWithoutInputMethod(JSONObject json) throws Exception {
        String action = json.optString("action", "");
        switch (action) {
            case "commit": {
                String text = json.optString("text", "");
                if (!text.isEmpty()) {
                    injectTextViaFocusedEditor(text);
                }
                break;
            }
            case "delete": {
                int count = Math.max(1, Math.min(json.optInt("count", 1), MAX_DIRECT_DELETE));
                for (int index = 0; index < count; index++) {
                    input.keyEvent(KEYCODE_DEL);
                }
                break;
            }
            case "clear": {
                input.keyEvent(KEYCODE_MOVE_END);
                for (int index = 0; index < MAX_DIRECT_DELETE; index++) {
                    input.keyEvent(KEYCODE_DEL);
                }
                break;
            }
            case "enter": {
                input.keyEvent(KEYCODE_ENTER);
                break;
            }
            default:
                break;
        }
    }

    /** Key events first (URLs, passwords, ASCII), clipboard paste for the remaining characters. */
    private void injectTextViaFocusedEditor(String text) throws Exception {
        if (input.injectTypedText(text)) {
            System.out.println("SkyADB text injected as key events without IME, textLength=" + text.length());
            return;
        }
        input.injectText(text);
        System.out.println("SkyADB text injected through clipboard without IME, textLength=" + text.length());
    }

    private void dismissInputMethodView() throws Exception {
        WebSocketConnection inputMethod = inputMethodClient;
        if (inputMethod != null) {
            inputMethod.sendText(new JSONObject().put("type", "imeDismiss").toString());
        }
    }

    /** True only when this box really exposes the SkyADB input method. Cached per process. */
    private boolean isInputMethodAvailable() {
        int cached = imeAvailability;
        if (cached == IME_AVAILABLE) {
            return true;
        }
        if (cached == IME_UNAVAILABLE) {
            return false;
        }
        String listed = runShellCapture("ime", "list", "-s");
        boolean available = listed != null && listed.contains(INPUT_METHOD);
        imeAvailability = available ? IME_AVAILABLE : IME_UNAVAILABLE;
        return available;
    }

    /** Best effort switch: boxes without any input method keep working through direct injection. */
    private void switchInputMethod() throws Exception {
        String current = readDefaultInputMethod();
        if (current != null
            && !current.isEmpty()
            && !"null".equals(current)
            && !INPUT_METHOD.equals(current)) {
            previousIme = current;
            writePreviousImeFile(current);
        }

        boolean selected;
        try {
            runImeCommand("enable", INPUT_METHOD);
            runImeCommand("set", INPUT_METHOD);
            selected = true;
        } catch (Exception error) {
            selected = false;
            System.out.println("SkyADB input method unavailable, using direct injection: "
                + error.getMessage());
        }
        imeAvailability = selected ? IME_AVAILABLE : IME_UNAVAILABLE;
        skyAdbImeActive = selected;
        if (!selected) {
            return;
        }

        waitForInputMethodClient();
        WebSocketConnection client = inputMethodClient;
        if (client == null) {
            skyAdbImeActive = false;
            imeAvailability = IME_UNAVAILABLE;
            System.out.println("SkyADB input method service did not connect, using direct injection");
            return;
        }
        client.sendText(new JSONObject().put("type", "imeShow").toString());
    }

    private void restorePreviousInputMethod() throws Exception {
        String target = previousIme;
        if (target == null || target.isEmpty() || "null".equals(target)) {
            target = readPreviousImeFile();
        }
        if (target == null || target.isEmpty() || "null".equals(target) || INPUT_METHOD.equals(target)) {
            target = findFallbackInputMethod();
        }
        if (target == null || target.isEmpty() || INPUT_METHOD.equals(target)) {
            skyAdbImeActive = false;
            System.out.println("SkyADB no previous IME available to restore");
            return;
        }
        try {
            runImeCommand("set", target);
        } catch (Exception error) {
            skyAdbImeActive = false;
            System.out.println("SkyADB previous IME could not be re-selected: " + error.getMessage());
            return;
        }
        skyAdbImeActive = false;
        System.out.println("SkyADB restored previous IME: " + target);
    }

    private WebSocketConnection waitForInputMethodClient() throws InterruptedException {
        long deadline = System.currentTimeMillis() + INPUT_METHOD_WAIT_MS;
        synchronized (inputMethodLock) {
            while (inputMethodClient == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                inputMethodLock.wait(Math.min(remaining, INPUT_METHOD_WAIT_STEP_MS));
            }
            return inputMethodClient;
        }
    }

    private static void runImeCommand(String action, String imeId) throws Exception {
        Process process = new ProcessBuilder("/system/bin/ime", action, imeId)
            .redirectErrorStream(true)
            .start();
        while (process.getInputStream().read() != -1) {
            // Drain the tiny command response so the process cannot block on a full pipe.
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 && !"enable".equals(action)) {
            throw new IllegalStateException("ime " + action + " failed for " + imeId);
        }
    }

    private static String readDefaultInputMethod() {
        return runShellCapture(
            "settings",
            "get",
            "secure",
            "default_input_method"
        );
    }

    private static String findFallbackInputMethod() {
        String listed = runShellCapture("ime", "list", "-s");
        if (listed == null || listed.isEmpty()) {
            return null;
        }
        String[] lines = listed.split("\\r?\\n");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && !INPUT_METHOD.equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String runShellCapture(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            process.waitFor();
            return output.toString().trim();
        } catch (Exception error) {
            return null;
        }
    }

    private static String readPreviousImeFile() {
        File file = new File(PREVIOUS_IME_FILE);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 512)];
            int read = input.read(data);
            if (read <= 0) {
                return null;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return null;
        }
    }

    private static void writePreviousImeFile(String imeId) {
        if (imeId == null || imeId.isEmpty()) {
            return;
        }
        File file = new File(PREVIOUS_IME_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(imeId.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            System.err.println("SkyADB failed to persist previous IME: " + error.getMessage());
        }
    }

    private static void sendOk(WebSocketConnection connection, String type) {
        try {
            connection.sendText(new JSONObject().put("ok", true).put("type", type).toString());
        } catch (Throwable ignored) {
            // The peer can disconnect immediately after sending a command.
        }
    }

    private static void requireCoordinates(JSONObject json, String type) {
        if (!json.has("x") || !json.has("y")) {
            throw new IllegalArgumentException(type + " requires x and y");
        }
    }

    private static void sendError(WebSocketConnection connection, String type, String message) {
        try {
            connection.sendText(
                new JSONObject()
                    .put("ok", false)
                    .put("type", type)
                    .put("error", message == null ? "Command failed" : message)
                    .toString()
            );
        } catch (Throwable ignored) {
            // The peer may already be disconnected.
        }
    }

    private static final int IME_UNKNOWN = -1;
    private static final int IME_UNAVAILABLE = 0;
    private static final int IME_AVAILABLE = 1;
    private static final int MAX_DIRECT_DELETE = 128;
    private static final String KEYCODE_DEL = "KEYCODE_DEL";
    private static final String KEYCODE_ENTER = "KEYCODE_ENTER";
    private static final String KEYCODE_MOVE_END = "KEYCODE_MOVE_END";
    private static final long INPUT_METHOD_WAIT_MS = 2_500L;
    private static final long INPUT_METHOD_WAIT_STEP_MS = 50L;
    private static final long INPUT_RESTORE_DELAY_MS = 180L;
}
