package com.porter.tvremote

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeAndLaunchKodiActionTest {

    @Test
    fun `waits for wake to settle before launching Kodi`() {
        val events = mutableListOf<String>()
        val action = WakeAndLaunchKodiAction(
            wake = { events += "wake" },
            sleep = { events += "sleep:$it" },
            launchKodi = { events += "launch" },
            isKodiForeground = {
                events += "verify"
                true
            },
        )

        action.run()

        assertEquals(
            listOf(
                "wake",
                "sleep:${WakeAndLaunchKodiAction.WAKE_SETTLE_DELAY_MS}",
                "launch",
                "sleep:${WakeAndLaunchKodiAction.KODI_VERIFY_DELAY_MS}",
                "verify",
            ),
            events,
        )
    }

    @Test
    fun `still launches Kodi when ADB wake fails during hardware wake`() {
        val events = mutableListOf<String>()
        val action = WakeAndLaunchKodiAction(
            wake = {
                events += "wake"
                error("ADB is still reconnecting")
            },
            sleep = { events += "sleep:$it" },
            launchKodi = { events += "launch" },
            isKodiForeground = { true },
            onWakeFailure = { events += "wake-failed" },
        )

        action.run()

        assertEquals(
            listOf(
                "wake",
                "wake-failed",
                "sleep:${WakeAndLaunchKodiAction.WAKE_SETTLE_DELAY_MS}",
                "launch",
                "sleep:${WakeAndLaunchKodiAction.KODI_VERIFY_DELAY_MS}",
            ),
            events,
        )
    }

    @Test
    fun `relaunches Kodi when Android kills the first launch during wake`() {
        val events = mutableListOf<String>()
        val action = WakeAndLaunchKodiAction(
            wake = { events += "wake" },
            sleep = { events += "sleep:$it" },
            launchKodi = { events += "launch" },
            isKodiForeground = {
                events += "verify"
                false
            },
        )

        action.run()

        assertEquals(
            listOf(
                "wake",
                "sleep:${WakeAndLaunchKodiAction.WAKE_SETTLE_DELAY_MS}",
                "launch",
                "sleep:${WakeAndLaunchKodiAction.KODI_VERIFY_DELAY_MS}",
                "verify",
                "launch",
            ),
            events,
        )
    }
}
