package com.server.skyadb.core;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class WebSocketConnection implements Runnable {
    interface Listener {
        void onOpen(WebSocketConnection connection);

        void onText(WebSocketConnection connection, String message);

        void onClose(WebSocketConnection connection);
    }

    private static final String WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private final Socket socket;
    private final Listener listener;
    private final Object writeLock = new Object();

    private InputStream input;
    private OutputStream output;
    private volatile boolean open;

    WebSocketConnection(Socket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            input = socket.getInputStream();
            output = socket.getOutputStream();
            performHandshake();
            open = true;
            listener.onOpen(this);
            readFrames();
        } catch (EOFException ignored) {
            // A peer may close a LAN connection without a WebSocket close frame.
        } catch (Throwable error) {
            if (open) {
                System.err.println("SkyADB WebSocket client error: " + error.getMessage());
            }
        } finally {
            close();
            listener.onClose(this);
        }
    }

    void sendText(String text) throws IOException {
        writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        boolean wasOpen = open;
        open = false;
        if (wasOpen) {
            try {
                writeFrame(0x8, new byte[0]);
            } catch (Throwable ignored) {
                // The socket may already be gone.
            }
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing else to release.
        }
    }

    private void performHandshake() throws Exception {
        String request = readHttpHeader();
        String[] lines = request.split("\\r\\n");
        if (lines.length == 0 || !lines[0].startsWith("GET ")) {
            throw new IOException("Not a WebSocket GET request");
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator > 0) {
                headers.put(
                    lines[i].substring(0, separator).trim().toLowerCase(Locale.US),
                    lines[i].substring(separator + 1).trim()
                );
            }
        }

        String key = headers.get("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            throw new IOException("Missing Sec-WebSocket-Key");
        }
        byte[] digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WEB_SOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1));
        String accept = Base64.encodeToString(digest, Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "X-SkyADB-Core: 7\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n"
            + "\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private String readHttpHeader() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (header.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            header.write(value);
            if ((byte) value == terminator[matched]) {
                matched++;
                if (matched == terminator.length) {
                    return header.toString(StandardCharsets.ISO_8859_1.name());
                }
            } else {
                matched = (byte) value == terminator[0] ? 1 : 0;
            }
        }
        throw new IOException("WebSocket request header is too large");
    }

    private void readFrames() throws IOException {
        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = 0;
        while (open) {
            int first = readByte();
            int second = readByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte();
                }
            }
            if (length < 0 || length > MAX_MESSAGE_BYTES) {
                throw new IOException("WebSocket frame is too large");
            }

            byte[] mask = masked ? readExact(4) : null;
            byte[] payload = readExact((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            if (opcode == 0x8) {
                return;
            }
            if (opcode == 0x9) {
                writeFrame(0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                continue;
            }
            if (opcode == 0x1 && fin) {
                listener.onText(this, new String(payload, StandardCharsets.UTF_8));
                continue;
            }
            if (opcode == 0x1) {
                fragmentedOpcode = opcode;
                fragmented = new ByteArrayOutputStream();
                fragmented.write(payload);
                continue;
            }
            if (opcode == 0x0 && fragmented != null) {
                fragmented.write(payload);
                if (fragmented.size() > MAX_MESSAGE_BYTES) {
                    throw new IOException("Fragmented WebSocket message is too large");
                }
                if (fin) {
                    if (fragmentedOpcode == 0x1) {
                        listener.onText(this, new String(fragmented.toByteArray(), StandardCharsets.UTF_8));
                    }
                    fragmented = null;
                    fragmentedOpcode = 0;
                }
            }
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (output == null) {
                throw new IOException("WebSocket is not ready");
            }
            output.write(0x80 | (opcode & 0x0f));
            if (payload.length <= 125) {
                output.write(payload.length);
            } else if (payload.length <= 0xffff) {
                output.write(126);
                output.write((payload.length >>> 8) & 0xff);
                output.write(payload.length & 0xff);
            } else {
                output.write(127);
                long longLength = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) ((longLength >>> shift) & 0xff));
                }
            }
            output.write(payload);
            output.flush();
        }
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException();
        }
        return value;
    }

    private byte[] readExact(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) {
                throw new EOFException();
            }
            offset += count;
        }
        return data;
    }
}
