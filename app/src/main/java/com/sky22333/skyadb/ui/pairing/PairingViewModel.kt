package com.sky22333.skyadb.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sky22333.skyadb.AppServices
import com.sky22333.skyadb.model.AdbOperationResult
import com.sky22333.skyadb.model.OperationStatus
import com.sky22333.skyadb.repository.AdbRepository
import com.sky22333.skyadb.validation.NetworkInputValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PairingUiState(
    val ip: String = "",
    val pairingPort: String = "",
    val pairingCode: String = "",
    val ipError: String? = null,
    val portError: String? = null,
    val codeError: String? = null,
    val pairEnabled: Boolean = false,
    val operationStatus: OperationStatus = OperationStatus.Idle,
)

class PairingViewModel(
    private val adbRepository: AdbRepository = AppServices.adbRepository,
) : ViewModel() {
    private val state = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = state.asStateFlow()

    fun onIpChanged(value: String) {
        updateForm(ip = value.trim(), pairingPort = state.value.pairingPort, pairingCode = state.value.pairingCode)
    }

    fun onPairingPortChanged(value: String) {
        updateForm(
            ip = state.value.ip,
            pairingPort = value.filter { it.isDigit() }.take(5),
            pairingCode = state.value.pairingCode,
        )
    }

    fun onPairingCodeChanged(value: String) {
        updateForm(
            ip = state.value.ip,
            pairingPort = state.value.pairingPort,
            pairingCode = value.filter { it.isDigit() }.take(6),
        )
    }

    fun onDiscoveredEndpointSelected(host: String, port: Int) {
        val currentCode = state.value.pairingCode
        val validation = validateForm(host, port.toString(), currentCode)
        state.value = state.value.copy(
            ip = host,
            pairingPort = port.toString(),
            ipError = validation.ipError,
            portError = validation.portError,
            codeError = validation.codeError,
            pairEnabled = validation.isValid,
            operationStatus = OperationStatus.Success("已填入自动发现的配对地址，请输入 6 位配对码。"),
        )
    }

    fun onPairClicked() {
        val current = state.value
        val validation = validateForm(current.ip, current.pairingPort, current.pairingCode)
        if (!validation.isValid) {
            state.value = current.copy(
                ipError = validation.ipError,
                portError = validation.portError,
                codeError = validation.codeError,
                pairEnabled = false,
                operationStatus = OperationStatus.Failed(
                    text = "无法发起配对",
                    suggestion = "请检查配对 IP、配对端口和 6 位配对码是否正确。",
                ),
            )
            return
        }

        state.value = current.copy(
            ipError = validation.ipError,
            portError = validation.portError,
            codeError = validation.codeError,
            pairEnabled = false,
            operationStatus = OperationStatus.Running("正在配对 ${current.ip}:${current.pairingPort}"),
        )

        viewModelScope.launch {
            when (
                val result = adbRepository.pair(
                    host = current.ip,
                    port = current.pairingPort.toInt(),
                    pairingCode = current.pairingCode,
                )
            ) {
                is AdbOperationResult.Success -> {
                    state.value = state.value.copy(
                        pairEnabled = true,
                        operationStatus = OperationStatus.Success("配对成功，请返回首页输入连接端口完成连接。"),
                    )
                }
                is AdbOperationResult.Failure -> {
                    state.value = state.value.copy(
                        pairEnabled = true,
                        operationStatus = OperationStatus.Failed(result.message, result.suggestion),
                    )
                }
            }
        }
    }

    private fun updateForm(ip: String, pairingPort: String, pairingCode: String) {
        val validation = validateForm(ip, pairingPort, pairingCode)
        state.value = state.value.copy(
            ip = ip,
            pairingPort = pairingPort,
            pairingCode = pairingCode,
            ipError = validation.ipError,
            portError = validation.portError,
            codeError = validation.codeError,
            pairEnabled = validation.isValid,
            operationStatus = OperationStatus.Idle,
        )
    }

    private fun validateForm(ip: String, pairingPort: String, pairingCode: String): PairingValidationResult {
        val ipError = NetworkInputValidator.ipv4Error(ip)
        val portError = NetworkInputValidator.portError(pairingPort, label = "配对端口")

        val codeError = when {
            pairingCode.isBlank() -> null
            pairingCode.length != 6 -> "配对码通常为 6 位数字"
            else -> null
        }

        return PairingValidationResult(
            ipError = ipError,
            portError = portError,
            codeError = codeError,
            isValid = ip.isNotBlank() &&
                pairingPort.isNotBlank() &&
                pairingCode.isNotBlank() &&
                ipError == null &&
                portError == null &&
                codeError == null,
        )
    }
}

private data class PairingValidationResult(
    val ipError: String?,
    val portError: String?,
    val codeError: String?,
    val isValid: Boolean,
)
