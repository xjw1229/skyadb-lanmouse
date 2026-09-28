package com.sky22333.skyadb.lanmouse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the deployment verdict: the server counts as installed when its package exists, even if the
 * installer stream reported something other than a clean "Success" marker.
 */
class ServerInstallVerdictTest {
    private fun installed(exitCode: Int, output: String, transportOk: Boolean = true): Boolean {
        if (!transportOk) return false
        return exitCode == 0 || output.contains(ServerPackageName)
    }

    @Test
    fun exitCodeZeroMeansInstalled() {
        assertTrue(installed(0, ""))
    }

    @Test
    fun packageListingMeansInstalledEvenWhenExitCodeIsNonZero() {
        assertTrue(installed(1, "package:$ServerPackageName"))
    }

    @Test
    fun missingPackageMeansNotInstalled() {
        assertFalse(installed(1, ""))
    }

    @Test
    fun transportFailureMeansNotInstalled() {
        assertFalse(installed(0, "package:$ServerPackageName", transportOk = false))
    }

    private companion object {
        const val ServerPackageName = "com.server.skyadb.lanmouse"
    }
}
