package com.sky22333.skyadb.model

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean = true,
)
