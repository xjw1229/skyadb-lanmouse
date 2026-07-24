package com.sky22333.skyadb.download

data class DownloadTask(
    val url: String,
    val fileName: String,
    val targetPath: String,
    val localPath: String? = null,
    val progress: Float,
    val state: DownloadState,
    val message: String = state.label,
)

enum class DownloadState(val label: String) {
    Waiting("等待下载"),
    Downloading("下载中"),
    Pushing("推送中"),
    Installing("安装中"),
    Success("已完成"),
    Failed("失败"),
    Canceled("已取消"),
}

sealed interface DownloadResult {
    data class Success(
        val fileName: String,
        val localPath: String,
    ) : DownloadResult

    data class Failure(
        val message: String,
        val suggestion: String,
        val cause: Throwable? = null,
    ) : DownloadResult

    data object Canceled : DownloadResult
}
