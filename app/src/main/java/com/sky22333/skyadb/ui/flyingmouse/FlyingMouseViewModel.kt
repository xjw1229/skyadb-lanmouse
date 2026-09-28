package com.sky22333.skyadb.ui.flyingmouse

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sky22333.skyadb.AppServices
import com.sky22333.skyadb.lanmouse.LanMouseConnectionStatus
import com.sky22333.skyadb.lanmouse.LanMouseServerManager
import com.sky22333.skyadb.model.AdbOperationResult
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class FlyingMouseUiState(
    val host: String = "",
    val connectionStatus: LanMouseConnectionStatus = LanMouseConnectionStatus.Disconnected,
    val deploying: Boolean = false,
    val deploymentMessage: String = "",
    val gyroscopeAvailable: Boolean = false,
    val gyroscopeEnabled: Boolean = false,
    val calibrating: Boolean = false,
    val sensitivity: Float = 14f,
    val inputText: String = "",
    val notice: String = "",
)

class FlyingMouseViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val client = AppServices.lanMouseClient
    private val serverManager = LanMouseServerManager(
        context = application,
        adbRepository = AppServices.adbRepository,
        kadbManager = AppServices.kadbManager,
    )
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val state = MutableStateFlow(
        FlyingMouseUiState(
            host = currentLanMouseHost(),
            connectionStatus = client.status.value,
            gyroscopeAvailable = gyroscope != null,
        ),
    )
    val uiState: StateFlow<FlyingMouseUiState> = state.asStateFlow()

    private var biasX = 0f
    private var biasZ = 0f
    private var calibrationCount = 0
    private var calibrationSumX = 0f
    private var calibrationSumZ = 0f
    private var remainderX = 0f
    private var remainderY = 0f
    private var touchpadRemainderX = 0f
    private var touchpadRemainderY = 0f
    private var dragRemainderX = 0f
    private var dragRemainderY = 0f
    private var lastEmitNanos = 0L
    private var connectionJob: Job? = null
    private var inputModeActive = false

    init {
        viewModelScope.launch {
            client.status.collect { status ->
                val visibleStatus = if (connectionJob?.isActive == true && status is LanMouseConnectionStatus.Failed) {
                    LanMouseConnectionStatus.Connecting
                } else {
                    status
                }
                val statusHost = if (status is LanMouseConnectionStatus.Connected) {
                    normalizeHost(status.endpoint.removePrefix("ws://"))
                } else {
                    ""
                }
                state.value = state.value.copy(
                    host = statusHost.ifBlank { state.value.host },
                    connectionStatus = visibleStatus,
                )
                if (status !is LanMouseConnectionStatus.Connected) {
                    inputModeActive = false
                }
                if (status !is LanMouseConnectionStatus.Connected && state.value.gyroscopeEnabled) {
                    setGyroscopeEnabled(false)
                }
            }
        }
    }

    fun setHost(value: String) {
        state.value = state.value.copy(host = normalizeHost(value), notice = "")
    }

    fun connect() {
        val host = normalizeHost(state.value.host)
        if (host.isBlank()) {
            state.value = state.value.copy(notice = "请先输入电视 IP")
            return
        }
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            state.value = state.value.copy(
                host = host,
                connectionStatus = LanMouseConnectionStatus.Connecting,
                notice = "正在检查电视端服务",
            )
            when (val result = serverManager.ensureRunning(host) { message ->
                state.value = state.value.copy(notice = message)
            }) {
                is AdbOperationResult.Success -> {
                    val readyHost = normalizeHost(result.data.ifBlank { host })
                    val recoveryNotice = if (serverManager.wasAdbRecoveryUsed() && !serverManager.wasRootAutostartInstalled()) {
                        "未获得 root，已用 ADB 轻量恢复；电视重启后需保持或重新连接 ADB"
                    } else {
                        ""
                    }
                    runConnectionLoop(
                        cleanHost = readyHost,
                        retries = 4,
                        retryDelayMs = 900L,
                        successNotice = recoveryNotice,
                    )
                }
                is AdbOperationResult.Failure -> {
                    val failed = LanMouseConnectionStatus.Failed(result.message)
                    state.value = state.value.copy(
                        connectionStatus = failed,
                        notice = result.suggestion,
                    )
                }
            }
            connectionJob = null
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        setGyroscopeEnabled(false)
        inputModeActive = false
        client.disconnect()
        // ADB backup path in case the websocket restore did not land.
        viewModelScope.launch(Dispatchers.IO) {
            serverManager.restorePreviousIme()
        }
    }

    fun deployServer() {
        if (state.value.deploying) return
        state.value = state.value.copy(
            deploying = true,
            deploymentMessage = "准备部署",
            notice = "",
        )
        viewModelScope.launch {
            when (val result = serverManager.deploy { message ->
                state.value = state.value.copy(deploymentMessage = message)
            }) {
                is AdbOperationResult.Success -> {
                    val host = result.data.ifBlank { state.value.host }
                    val startupNotice = if (serverManager.wasRootAutostartInstalled()) {
                        if (host.isBlank()) {
                            "已配置 root 真开机自启，请输入电视 IP 后连接"
                        } else {
                            "已配置 root 真开机自启，正在连接 $host"
                        }
                    } else {
                        "未获得 root，本次已用 ADB 临时启动；电视重启后可通过 ADB 轻量恢复"
                    }
                    state.value = state.value.copy(
                        host = host,
                        deploying = false,
                        deploymentMessage = "电视端服务已启动",
                        notice = startupNotice,
                    )
                    if (host.isNotBlank()) {
                        startConnection(
                            host = host,
                            retries = 4,
                            retryDelayMs = 900L,
                            successNotice = if (serverManager.wasRootAutostartInstalled()) "" else startupNotice,
                        )
                    }
                }
                is AdbOperationResult.Failure -> {
                    state.value = state.value.copy(
                        deploying = false,
                        deploymentMessage = result.message,
                        notice = result.suggestion,
                    )
                }
            }
        }
    }

    fun setSensitivity(value: Float) {
        state.value = state.value.copy(sensitivity = value.coerceIn(5f, 30f))
    }

    fun toggleGyroscope() {
        setGyroscopeEnabled(!state.value.gyroscopeEnabled)
    }

    fun recalibrate() {
        if (!state.value.gyroscopeEnabled) return
        beginCalibration()
    }

    fun moveByTouchpad(dx: Float, dy: Float) {
        // Finger travel arrives as fractional pixels. Truncating each frame dropped every slow
        // slide (sub-pixel deltas became 0), so carry the remainder like the sensor path does.
        touchpadRemainderX += dx
        touchpadRemainderY += dy
        val stepX = touchpadRemainderX.toInt()
        val stepY = touchpadRemainderY.toInt()
        touchpadRemainderX -= stepX
        touchpadRemainderY -= stepY
        if (stepX != 0 || stepY != 0) {
            client.moveRelative(stepX, stepY)
        }
    }

    fun tap() {
        client.tapHere()
    }

    fun longPress() {
        client.longPressHere()
    }

    /**
     * Presses at the current pointer position without releasing.
     *
     * <p>A short tap is fine for buttons, but progress bars, sliders and long-press menus only
     * react to a real press-move-release sequence. This starts that sequence so the following
     * [dragBy] calls are reported as finger movement instead of pointer hover.
     */
    fun beginDrag() {
        dragRemainderX = 0f
        dragRemainderY = 0f
        client.touchDownHere()
    }

    fun dragBy(dx: Float, dy: Float) {
        dragRemainderX += dx
        dragRemainderY += dy
        val stepX = dragRemainderX.toInt()
        val stepY = dragRemainderY.toInt()
        dragRemainderX -= stepX
        dragRemainderY -= stepY
        if (stepX != 0 || stepY != 0) {
            client.dragTouchBy(stepX, stepY)
        }
    }

    fun endDrag() {
        client.touchUpHere()
    }

    fun sendKey(keyCode: String) {
        client.keyEvent(keyCode)
    }

    fun beginScroll(vertical: Boolean, direction: Float) {
        client.touchDownForScroll(vertical, direction)
    }

    fun scrollBy(dx: Float, dy: Float) {
        client.dragTouchAccumulated(dx.toInt(), dy.toInt())
    }

    fun endScroll() {
        client.touchUpHere()
    }

    fun setInputText(value: String) {
        state.value = state.value.copy(inputText = value)
    }

    fun activateInputMode() {
        if (inputModeActive) return
        if (client.activateCursorInputMethod()) {
            inputModeActive = true
        } else {
            state.value = state.value.copy(notice = "请先连接电视端服务")
        }
    }

    fun deactivateInputMode() {
        if (!inputModeActive) return
        inputModeActive = false
        viewModelScope.launch(Dispatchers.IO) {
            client.dismissCursorInputMethod()
            client.restorePreviousInputMethod()
            serverManager.restorePreviousIme()
        }
    }

    fun sendInputText() {
        val text = state.value.inputText
        if (text.isBlank()) return
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                client.commitTextAndRestore(text)
            }
            inputModeActive = false
            state.value = if (sent) {
                state.value.copy(inputText = "", notice = "文本已发送")
            } else {
                val error = client.lastCommandError().ifBlank { "请重新一键部署电视端服务后再试" }
                state.value.copy(notice = "发送失败：$error")
            }
        }
    }

    fun deleteRemoteCharacter() {
        if (!client.deleteBackward()) {
            state.value = state.value.copy(notice = "请先连接电视端服务")
        } else {
            inputModeActive = true
        }
    }

    private fun startConnection(
        host: String,
        retries: Int,
        retryDelayMs: Long,
        successNotice: String = "",
    ) {
        val cleanHost = normalizeHost(host)
        if (cleanHost.isBlank()) return
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            runConnectionLoop(cleanHost, retries, retryDelayMs, successNotice)
            connectionJob = null
        }
    }

    private suspend fun runConnectionLoop(
        cleanHost: String,
        retries: Int,
        retryDelayMs: Long,
        successNotice: String = "",
    ) {
        state.value = state.value.copy(
            host = cleanHost,
            connectionStatus = LanMouseConnectionStatus.Connecting,
            notice = successNotice,
        )
        repeat(retries + 1) { attempt ->
            if (!currentCoroutineContext().isActive) return
            client.connect(cleanHost)
            val outcome = withTimeoutOrNull(6_500L) {
                client.status.first { it !is LanMouseConnectionStatus.Connecting }
            } ?: LanMouseConnectionStatus.Failed("连接超时")

            when (outcome) {
                is LanMouseConnectionStatus.Connected -> {
                    state.value = state.value.copy(connectionStatus = outcome, notice = successNotice)
                    return
                }

                is LanMouseConnectionStatus.Failed -> {
                    if (attempt == retries) {
                        state.value = state.value.copy(connectionStatus = outcome, notice = outcome.message)
                        return
                    }
                    state.value = state.value.copy(notice = "连接中，正在重试")
                }

                is LanMouseConnectionStatus.Disconnected -> {
                    if (attempt == retries) {
                        val failed = LanMouseConnectionStatus.Failed("连接已断开")
                        state.value = state.value.copy(connectionStatus = failed, notice = failed.message)
                        return
                    }
                }

                LanMouseConnectionStatus.Connecting -> Unit
            }

            if (attempt < retries) delay(retryDelayMs)
        }
    }

    private fun setGyroscopeEnabled(enabled: Boolean) {
        if (!enabled) {
            sensorManager.unregisterListener(this)
            state.value = state.value.copy(gyroscopeEnabled = false, calibrating = false)
            return
        }

        if (client.status.value !is LanMouseConnectionStatus.Connected) {
            state.value = state.value.copy(notice = "请先连接电视端服务")
            return
        }
        val sensor = gyroscope
        if (sensor == null) {
            state.value = state.value.copy(notice = "这台手机没有陀螺仪传感器")
            return
        }

        beginCalibration()
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        state.value = state.value.copy(gyroscopeEnabled = true, notice = "保持手机静止片刻以完成校准")
    }

    private fun beginCalibration() {
        calibrationCount = 0
        calibrationSumX = 0f
        calibrationSumZ = 0f
        biasX = 0f
        biasZ = 0f
        remainderX = 0f
        remainderY = 0f
        lastEmitNanos = 0L
        state.value = state.value.copy(calibrating = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!state.value.gyroscopeEnabled || event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        if (calibrationCount < CalibrationSamples) {
            calibrationSumX += event.values[0]
            calibrationSumZ += event.values[2]
            calibrationCount++
            if (calibrationCount == CalibrationSamples) {
                biasX = calibrationSumX / CalibrationSamples
                biasZ = calibrationSumZ / CalibrationSamples
                state.value = state.value.copy(calibrating = false, notice = "陀螺仪已校准")
            }
            return
        }

        if (lastEmitNanos != 0L && event.timestamp - lastEmitNanos < EmitIntervalNanos) return
        lastEmitNanos = event.timestamp

        val horizontal = -(event.values[2] - biasZ)
        val vertical = -(event.values[0] - biasX)
        val filteredHorizontal = horizontal.takeUnless { abs(it) < DeadZone } ?: 0f
        val filteredVertical = vertical.takeUnless { abs(it) < DeadZone } ?: 0f
        val scale = state.value.sensitivity

        remainderX += filteredHorizontal * scale
        remainderY += filteredVertical * scale
        val dx = remainderX.toInt()
        val dy = remainderY.toInt()
        remainderX -= dx
        remainderY -= dy

        if (dx != 0 || dy != 0) {
            client.moveRelative(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        sensorManager.unregisterListener(this)
        if (inputModeActive) {
            client.dismissCursorInputMethod()
            client.restorePreviousInputMethod()
            inputModeActive = false
        }
        super.onCleared()
    }

    private fun currentLanMouseHost(): String {
        val connected = client.status.value as? LanMouseConnectionStatus.Connected
        return connected?.endpoint
            ?.removePrefix("ws://")
            ?.let(::normalizeHost)
            ?.ifBlank { null }
            ?: serverManager.currentHost()
    }

    private fun normalizeHost(value: String): String {
        return value.trim()
            .removePrefix("ws://")
            .removePrefix("http://")
            .substringBefore('/')
            .removeSuffix(":19870")
    }

    private companion object {
        const val CalibrationSamples = 24
        const val EmitIntervalNanos = 16_000_000L
        const val DeadZone = 0.035f
    }
}
