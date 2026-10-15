package space.sovereigncast.hopp.feed

import space.sovereigncast.hopp.model.Funding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Device feedback 2026-10-15: which funding links Hopp pays itself. */
class FundingTest {
    @Test
    fun aLightningAddressLinkIsPaidInHopp() {
        assertEquals("nodesignal@getalby.com", Funding("lightning:nodesignal@getalby.com", null).lightningAddress)
        assertEquals("nodesignal@getalby.com", Funding(" LIGHTNING:nodesignal@getalby.com ", null).lightningAddress)
    }

    @Test
    fun everythingElseOpensOutside() {
        assertNull(Funding("https://fund.test/", null).lightningAddress)
        assertNull(Funding("lightning:lnurl1dp68gurn8ghj7", null).lightningAddress)
        assertNull(Funding("lightning:lnbc10n1p…", null).lightningAddress)
        assertNull(Funding("lightning:a@b", null).lightningAddress)
        assertNull(Funding("nodesignal@getalby.com", null).lightningAddress)
    }
}
