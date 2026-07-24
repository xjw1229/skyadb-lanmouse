package com.sky22333.skyadb.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test
    fun record_keepsLatestLogsWithinLimit() {
        DiagnosticLogger.clear()

        repeat(505) { index ->
            DiagnosticLogger.record(
                module = DiagnosticModule.WifiAdb,
                operation = "连接设备",
                target = "192.168.1.$index:5555",
                message = "失败 $index",
                suggestion = "检查网络",
            )
        }

        val logs = DiagnosticLogger.logs.value
        assertEquals(500, logs.size)
        assertEquals("失败 504", logs.first().message)
        assertEquals("失败 5", logs.last().message)
    }

    @Test
    fun record_dropsImmediateDuplicate() {
        DiagnosticLogger.clear()

        repeat(2) {
            DiagnosticLogger.record(
                module = DiagnosticModule.App,
                operation = "初始化 ADB 身份",
                message = "ADB 身份初始化失败",
                suggestion = "重试",
            )
        }

        assertEquals(1, DiagnosticLogger.logs.value.size)
    }

    @Test
    fun formatter_includesUsefulFields() {
        val text = DiagnosticFormatter.format(
            DiagnosticLog(
                id = 1,
                timeMillis = 0,
                module = DiagnosticModule.Files,
                operation = "删除文件",
                target = "/sdcard/Pictures/a.png",
                message = "text file busy",
                suggestion = "关闭占用文件的应用后重试",
            ),
        )

        assertTrue(text.contains("文件"))
        assertTrue(text.contains("删除文件"))
        assertTrue(text.contains("text file busy"))
    }
}
