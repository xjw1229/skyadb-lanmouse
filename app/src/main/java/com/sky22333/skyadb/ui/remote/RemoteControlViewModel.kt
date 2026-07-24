package com.sky22333.skyadb.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sky22333.skyadb.AppServices
import com.sky22333.skyadb.model.AdbOperationResult
import com.sky22333.skyadb.model.OperationStatus
import com.sky22333.skyadb.repository.AdbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RemoteControlUiState(
    val status: OperationStatus = OperationStatus.Idle,
)

enum class RemoteKey(val label: String, val keyCode: String) {
    Power("电源", "KEYCODE_POWER"),
    Wakeup("唤醒", "KEYCODE_WAKEUP"),
    Sleep("息屏", "KEYCODE_SLEEP"),
    Home("主页", "KEYCODE_HOME"),
    Back("返回", "KEYCODE_BACK"),
    Menu("菜单", "KEYCODE_MENU"),
    Up("上", "KEYCODE_DPAD_UP"),
    Down("下", "KEYCODE_DPAD_DOWN"),
    Left("左", "KEYCODE_DPAD_LEFT"),
    Right("右", "KEYCODE_DPAD_RIGHT"),
    Center("确认", "KEYCODE_DPAD_CENTER"),
    VolumeUp("音量+", "KEYCODE_VOLUME_UP"),
    VolumeDown("音量-", "KEYCODE_VOLUME_DOWN"),
    Mute("静音", "KEYCODE_VOLUME_MUTE"),
    PlayPause("播放/暂停", "KEYCODE_MEDIA_PLAY_PAUSE"),
    Previous("上一首", "KEYCODE_MEDIA_PREVIOUS"),
    Next("下一首", "KEYCODE_MEDIA_NEXT"),
}

class RemoteControlViewModel(
    private val adbRepository: AdbRepository = AppServices.adbRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = state.asStateFlow()

    fun sendKey(key: RemoteKey) {
        state.value = state.value.copy(status = OperationStatus.Running("正在发送 ${key.label}"))
        viewModelScope.launch {
            when (val result = adbRepository.runShell("input keyevent ${key.keyCode}")) {
                is AdbOperationResult.Success -> {
                    state.value = if (result.data.exitCode == 0) {
                        state.value.copy(status = OperationStatus.Success("已发送：${key.label}"))
                    } else {
                        state.value.copy(
                            status = OperationStatus.Failed(
                                text = "按键发送失败",
                                suggestion = result.data.errorOutput.toRemoteInputSuggestion(),
                            ),
                        )
                    }
                }
                is AdbOperationResult.Failure -> {
                    state.value = state.value.copy(
                        status = OperationStatus.Failed(result.message, result.suggestion),
                    )
                }
            }
        }
    }
}

internal fun String.toRemoteInputSuggestion(): String {
    return when {
        contains("INJECT_EVENTS", ignoreCase = true) ||
            contains("Injecting input events", ignoreCase = true) ->
            "目标设备禁止 ADB 按键控制，请检查开发者选项中的安全调试设置。"
        else -> lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?: "请确认目标设备仍保持连接。"
    }
}
