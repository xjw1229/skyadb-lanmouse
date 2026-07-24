package com.sky22333.skyadb.validation

object DevicePathValidator {
    fun pathError(value: String, label: String = "设备路径"): String? {
        return when {
            value.isBlank() -> null
            !value.startsWith("/") -> "${label}需要以 / 开头"
            else -> null
        }
    }
}
