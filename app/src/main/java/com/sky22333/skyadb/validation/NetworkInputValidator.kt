package com.sky22333.skyadb.validation

object NetworkInputValidator {
    fun ipv4Error(value: String): String? {
        return when {
            value.isBlank() -> null
            !isValidIpv4(value) -> "请输入正确的 IPv4 地址"
            else -> null
        }
    }

    fun portError(value: String, label: String = "端口"): String? {
        val portNumber = value.toIntOrNull()
        return when {
            value.isBlank() -> null
            portNumber == null -> "${label}只能填写数字"
            portNumber !in 1..65535 -> "${label}范围应为 1-65535"
            else -> null
        }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it.isDigit() } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
