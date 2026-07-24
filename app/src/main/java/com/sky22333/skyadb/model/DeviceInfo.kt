package com.sky22333.skyadb.model

data class DeviceInfo(
    val brand: String = "未知",
    val model: String = "未知",
    val androidVersion: String = "未知",
    val sdk: String = "未知",
    val abi: String = "未知",
    val resolution: String = "未知",
    val battery: String = "未知",
)
