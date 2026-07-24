package com.sky22333.skyadb.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInputValidatorTest {
    @Test
    fun isHttpUrl_acceptsHttpAndHttps() {
        assertTrue(DownloadInputValidator.isHttpUrl("https://example.com/app.apk"))
        assertTrue(DownloadInputValidator.isHttpUrl("http://example.com/file.zip"))
    }

    @Test
    fun isHttpUrl_rejectsUnsupportedSchemes() {
        assertFalse(DownloadInputValidator.isHttpUrl("ftp://example.com/file.zip"))
        assertFalse(DownloadInputValidator.isHttpUrl("content://downloads/file.apk"))
    }

    @Test
    fun urlError_checksApkSuffixWhenRequired() {
        assertNull(DownloadInputValidator.urlError("https://example.com/app.apk", requireApk = true))
        assertNull(DownloadInputValidator.urlError("https://example.com/app.apk?token=abc", requireApk = true))
        assertEquals(
            "APK 下载链接建议以 .apk 结尾",
            DownloadInputValidator.urlError("https://example.com/download?id=1", requireApk = true),
        )
    }

    @Test
    fun urlError_rejectsNonHttpUrl() {
        assertEquals(
            "请输入以 http:// 或 https:// 开头的链接",
            DownloadInputValidator.urlError("ftp://example.com/file.apk"),
        )
    }
}
