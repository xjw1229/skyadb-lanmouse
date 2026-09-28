package com.sky22333.skyadb.lanmouse

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed interface LanMouseConnectionStatus {
    data object Disconnected : LanMouseConnectionStatus
    data object Connecting : LanMouseConnectionStatus
    data class Connected(val endpoint: String) : LanMouseConnectionStatus
    data class Failed(val message: String) : LanMouseConnectionStatus
}

/**
 * 局域网飞鼠客户端。
 *
 * 电视端 SkyADB 核心监听 19870，普通手机连接会自动按 APP 客户端处理，
 * 不需要云端账号或额外握手。
 */
class LanMouseClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val statusState = MutableStateFlow<LanMouseConnectionStatus>(LanMouseConnectionStatus.Disconnected)
    val status: StateFlow<LanMouseConnectionStatus> = statusState.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var screenWidth: Int = 0

    @Volatile
    private var screenHeight: Int = 0

    private val ackLock = Any()
    @Volatile
    private var lastAckType: String? = null
    @Volatile
    private var lastAckOk: Boolean = false
    @Volatile
    private var lastAckMessage: String = ""

    fun connect(host: String, port: Int = DefaultPort) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            statusState.value = LanMouseConnectionStatus.Failed("请输入电视 IP 地址")
            return
        }

        disconnect()
        screenWidth = 0
        screenHeight = 0
        statusState.value = LanMouseConnectionStatus.Connecting
        val endpoint = "ws://$cleanHost:$port"
        val request = Request.Builder().url(endpoint).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket === webSocket) {
                    val protocolVersion = response.header("X-SkyADB-Core")
                    if (protocolVersion != CoreProtocolVersion) {
                        socket = null
                        webSocket.close(1000, "outdated core")
                        statusState.value = LanMouseConnectionStatus.Failed(
                            "电视端服务版本过旧，请重新一键部署",
                        )
                        return
                    }
                    statusState.value = LanMouseConnectionStatus.Connected(endpoint)
                    requestCursorInfo()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket === webSocket) {
                    updateScreenSize(text)
                    trackAck(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) {
                    socket = null
                    statusState.value = LanMouseConnectionStatus.Disconnected
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (socket === webSocket) {
                    socket = null
                    statusState.value = LanMouseConnectionStatus.Failed(
                        throwable.message ?: "无法连接电视端服务",
                    )
                }
            }
        }
        socket = httpClient.newWebSocket(request, listener)
    }

    fun disconnect() {
        // Ask the TV core to restore the previous IME before tearing down the socket.
        restorePreviousInputMethod()
        val current = socket
        socket = null
        current?.close(1000, "client disconnect")
        current?.cancel()
        statusState.value = LanMouseConnectionStatus.Disconnected
    }

    fun moveRelative(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        return send(
            JSONObject()
                .put("type", "moveCursor")
                .put("x", dx.coerceIn(-120, 120))
                .put("y", dy.coerceIn(-120, 120))
                .put("absolute", false),
        )
    }

    fun tapHere(durationMs: Int = 100): Boolean = send(
        JSONObject()
            .put("type", "tapHere")
            .put("duration", durationMs.coerceIn(40, 1_000)),
    )

    fun longPressHere(durationMs: Int = 650): Boolean = send(
        JSONObject()
            .put("type", "longPressHere")
            .put("duration", durationMs.coerceIn(300, 3_000)),
    )

    fun keyEvent(event: String): Boolean = send(
        JSONObject()
            .put("type", "keyEvent")
            .put("event", event),
    )

    fun touchDownHere(): Boolean = send(JSONObject().put("type", "touchDown"))

    fun touchDownForScroll(vertical: Boolean, direction: Float): Boolean {
        return send(
            JSONObject()
                .put("type", "touchDownRatio")
                .put("rx", scrollAnchorRatioX(vertical, direction))
                .put("ry", scrollAnchorRatioY(vertical, direction)),
        )
    }

    fun dragTouchAccumulated(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        return send(
            JSONObject()
                .put("type", "touchMove")
                .put("dx", dx.coerceIn(-4_096, 4_096))
                .put("dy", dy.coerceIn(-4_096, 4_096))
                .put("accumulated", true),
        )
    }

    /**
     * Moves an already-pressed finger by an incremental offset.
     *
     * <p>[dragTouchAccumulated] reports offsets from the scroll anchor, which is right for the
     * scroll zones. A press-and-drag needs each move measured from the previous position, so it
     * sends {@code accumulated = false}.
     */
    fun dragTouchBy(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        return send(
            JSONObject()
                .put("type", "touchMove")
                .put("dx", dx.coerceIn(-4_096, 4_096))
                .put("dy", dy.coerceIn(-4_096, 4_096))
                .put("accumulated", false),
        )
    }

    private fun scrollAnchorRatioX(vertical: Boolean, direction: Float): Double {
        val directionalRatio = if (direction < 0f) 0.90 else 0.10
        return if (vertical) 0.50 else directionalRatio
    }

    private fun scrollAnchorRatioY(vertical: Boolean, direction: Float): Double {
        val directionalRatio = if (direction < 0f) 0.90 else 0.10
        return if (vertical) directionalRatio else 0.50
    }

    fun touchUpHere(): Boolean = send(JSONObject().put("type", "touchUp"))

    fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false
        return sendAndAwait(
            expectedType = "inputText",
            message = JSONObject()
                .put("type", "inputText")
                .put("text", text),
        )
    }

    fun commitText(text: String): Boolean {
        if (text.isEmpty()) return false
        return sendAndAwait(
            expectedType = "imeInput",
            message = JSONObject()
                .put("type", "imeInput")
                .put("action", "commit")
                .put("text", text),
        )
    }

    fun commitTextAndRestore(text: String): Boolean {
        if (text.isEmpty()) return false
        return sendAndAwait(
            expectedType = "imeInputDone",
            message = JSONObject()
                .put("type", "imeInputDone")
                .put("action", "commit")
                .put("text", text),
        )
    }

    fun deleteBackward(count: Int = 1): Boolean = send(
        JSONObject()
            .put("type", "imeInput")
            .put("action", "delete")
            .put("count", count.coerceIn(1, 100)),
    )

    fun activateCursorInputMethod(): Boolean = send(
        JSONObject()
            .put("type", "switchIme")
            .put("ime", CursorInputMethod),
    )

    fun dismissCursorInputMethod(): Boolean = sendAndAwait(
        expectedType = "imeDismiss",
        message = JSONObject().put("type", "imeDismiss"),
    )

    fun restorePreviousInputMethod(): Boolean = send(
        JSONObject().put("type", "restoreIme"),
    )

    private fun requestCursorInfo(): Boolean = send(JSONObject().put("type", "cursorInfo"))

    private fun updateScreenSize(message: String) {
        runCatching {
            val response = JSONObject(message)
            if (response.optString("type") != "cursorInfo" || !response.optBoolean("ok")) return@runCatching
            val data = response.optJSONObject("data") ?: return@runCatching
            val width = data.optInt("screenWidth")
            val height = data.optInt("screenHeight")
            if (width > 0 && height > 0) {
                screenWidth = width
                screenHeight = height
            }
        }
    }

    fun lastCommandError(): String = lastAckMessage

    private fun trackAck(message: String) {
        runCatching {
            val response = JSONObject(message)
            val type = response.optString("type")
            if (type.isBlank() || type == "cursorInfo" || type == "imeCursor") return@runCatching
            synchronized(ackLock) {
                lastAckType = type
                lastAckOk = response.optBoolean("ok", false)
                lastAckMessage = response.optString("error", response.optString("message", ""))
                (ackLock as Object).notifyAll()
            }
        }
    }

    private fun sendAndAwait(
        expectedType: String,
        message: JSONObject,
        timeoutMs: Long = 2_500L,
    ): Boolean {
        val current = socket ?: return false
        if (statusState.value !is LanMouseConnectionStatus.Connected) return false
        synchronized(ackLock) {
            lastAckType = null
            lastAckOk = false
            lastAckMessage = ""
            if (!current.send(message.toString())) return false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (lastAckType != expectedType && System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                (ackLock as Object).wait(remaining.coerceAtMost(100L))
            }
            if (lastAckType != expectedType && lastAckMessage.isBlank()) {
                lastAckMessage = "电视端未确认输入命令"
            }
            return lastAckType == expectedType && lastAckOk
        }
    }

    private fun send(message: JSONObject): Boolean {
        val current = socket ?: return false
        if (statusState.value !is LanMouseConnectionStatus.Connected) return false
        return current.send(message.toString())
    }

    fun shutdown() {
        disconnect()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private companion object {
        const val DefaultPort = 19_870
        const val CoreProtocolVersion = "7"
        const val CursorInputMethod = "com.server.skyadb.lanmouse/.SkyAdbInputMethodService"
    }
}
