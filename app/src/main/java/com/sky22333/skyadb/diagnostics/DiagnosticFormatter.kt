package com.sky22333.skyadb.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticFormatter {
    fun format(logs: List<DiagnosticLog>): String {
        return if (logs.isEmpty()) {
            "暂无诊断日志"
        } else {
            logs.joinToString(separator = "\n\n") { format(it) }
        }
    }

    fun format(log: DiagnosticLog): String {
        return buildString {
            appendLine("时间：${formatTime(log.timeMillis)}")
            appendLine("模块：${log.module.label}")
            appendLine("操作：${log.operation}")
            log.target?.let { appendLine("目标：$it") }
            appendLine("原因：${log.message}")
            appendLine("建议：${log.suggestion}")
            if (log.errorClass != null || log.errorMessage != null) {
                append("异常：")
                append(log.errorClass.orEmpty())
                log.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
        }
    }

    fun formatTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
    }
}
