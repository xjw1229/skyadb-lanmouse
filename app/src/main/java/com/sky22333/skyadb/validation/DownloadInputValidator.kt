package com.sky22333.skyadb.validation

object DownloadInputValidator {
    fun urlError(value: String, requireApk: Boolean = false): String? {
        val normalized = value.trim()
        return when {
            normalized.isBlank() -> null
            !isHttpUrl(normalized) -> "请输入以 http:// 或 https:// 开头的链接"
            requireApk && !normalized.substringBefore("?").endsWith(".apk", ignoreCase = true) -> {
                "APK 下载链接建议以 .apk 结尾"
            }
            else -> null
        }
    }

    fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
