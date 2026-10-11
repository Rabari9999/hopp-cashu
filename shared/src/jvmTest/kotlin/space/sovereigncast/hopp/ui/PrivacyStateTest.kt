package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.platform.NetworkPrivacy
import space.sovereigncast.hopp.ui.library.PrivacyState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The privacy page's checks (device feedback 2026-10-11): done only where Hopp can see it. */
class PrivacyStateTest {
    private fun state(s: Settings = Settings(), net: NetworkPrivacy = NetworkPrivacy()) = PrivacyState(s, null, net)

    @Test fun defaultsOnAPhoneThatCannotTellCountOnlyHoppsOwnSettings() {
        // Directories off, no name — both done; VPN and DNS unknown, so not counted.
        assertEquals(listOf(true, true), state().checks)
    }

    @Test fun vpnCoversDnsToo() {
        val s = state(net = NetworkPrivacy(vpn = true, privateDns = false))
        assertEquals(true, s.dns)
        assertEquals(4, s.checks.count { it })
    }

    @Test fun whatIsLeftToDoIsCounted() {
        val s = state(Settings(fyydSearch = true, senderName = "Satoshi"), NetworkPrivacy(vpn = false, privateDns = true))
        assertEquals(listOf(false, true, false, false), s.checks)
    }

    @Test fun aNameOfSpacesIsNoName() = assertEquals(true, state(Settings(senderName = "  ")).anonymous)
}
