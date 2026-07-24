package com.sky22333.skyadb.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePathValidatorTest {
    @Test
    fun pathError_acceptsAbsoluteDevicePath() {
        assertNull(DevicePathValidator.pathError("/sdcard/Download/"))
        assertNull(DevicePathValidator.pathError("/sdcard/Download/file.txt"))
    }

    @Test
    fun pathError_rejectsRelativePath() {
        assertEquals("设备路径需要以 / 开头", DevicePathValidator.pathError("sdcard/Download/file.txt"))
        assertEquals("目标路径需要以 / 开头", DevicePathValidator.pathError("sdcard/Download/", label = "目标路径"))
    }
}
