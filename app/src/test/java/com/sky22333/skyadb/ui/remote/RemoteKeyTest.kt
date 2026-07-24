package com.sky22333.skyadb.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteKeyTest {
    @Test
    fun keyCodes_matchAndroidInputKeyEvents() {
        assertEquals("KEYCODE_BACK", RemoteKey.Back.keyCode)
        assertEquals("KEYCODE_DPAD_CENTER", RemoteKey.Center.keyCode)
        assertEquals("KEYCODE_MEDIA_PLAY_PAUSE", RemoteKey.PlayPause.keyCode)
    }

    @Test
    fun inputPermissionError_usesCompactSuggestion() {
        val error = "java.lang.SecurityException: Injecting input events requires INJECT_EVENTS permission"

        assertEquals(
            "目标设备禁止 ADB 按键控制，请检查开发者选项中的安全调试设置。",
            error.toRemoteInputSuggestion(),
        )
    }
}
