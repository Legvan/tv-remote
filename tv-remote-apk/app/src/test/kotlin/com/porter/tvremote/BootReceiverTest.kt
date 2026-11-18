package com.porter.tvremote

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    private val ownPackage = "com.porter.tvremote"

    // Both as `dumpsys activity broadcasts history` lists them on the API 37 emulator.
    /** Sent when the app is opened after a force-stop: pkg=com.porter.tvremote. */
    private val leavingStoppedFlags = 0x09000030

    /** Sent at boot to every app, no package. */
    private val bootFlags = 0x89000010L.toInt()

    @Test
    fun `ignores the BOOT_COMPLETED for leaving the stopped state`() {
        assertTrue(leaving(35, Intent.ACTION_BOOT_COMPLETED, ownPackage, leavingStoppedFlags))
        assertTrue(leaving(37, Intent.ACTION_BOOT_COMPLETED, ownPackage, leavingStoppedFlags))
    }

    @Test
    fun `starts on the BOOT_COMPLETED sent at boot`() {
        assertFalse(leaving(35, Intent.ACTION_BOOT_COMPLETED, null, bootFlags))
        assertFalse(leaving(37, Intent.ACTION_BOOT_COMPLETED, null, bootFlags))
    }

    @Test
    fun `needs both the package and the stopped-packages flag`() {
        assertFalse(leaving(35, Intent.ACTION_BOOT_COMPLETED, ownPackage, bootFlags))
        assertFalse(leaving(35, Intent.ACTION_BOOT_COMPLETED, null, leavingStoppedFlags))
    }

    @Test
    fun `filters nothing before Android 15`() {
        assertFalse(leaving(34, Intent.ACTION_BOOT_COMPLETED, ownPackage, leavingStoppedFlags))
    }

    @Test
    fun `still starts after an app update`() {
        assertFalse(leaving(35, Intent.ACTION_MY_PACKAGE_REPLACED, ownPackage, leavingStoppedFlags))
    }

    private fun leaving(sdkInt: Int, action: String, targetPackage: String?, flags: Int) =
        BootReceiver.isLeavingStoppedState(sdkInt, action, targetPackage, flags)
}
