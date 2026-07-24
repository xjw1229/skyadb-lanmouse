package com.sky22333.skyadb.ui.flyingmouse

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteKeyTest {
    @Test
    fun confirm_uses_the_same_dpad_center_event_as_the_direction_pad() {
        assertEquals("KEYCODE_DPAD_CENTER", RemoteKey.Confirm.code)
    }
}
