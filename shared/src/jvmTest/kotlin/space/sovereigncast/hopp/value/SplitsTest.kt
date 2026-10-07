package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.model.ValueTimeSplit
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitsTest {
    private fun r(name: String, split: Int, fee: Boolean = false, address: String = "addr-$name") =
        ValueRecipient(name, "node", address, split, fee)

    private fun amounts(shares: List<Share>) = shares.associate { it.recipient.name!! to it.amountMsat }

    @Test
    fun sharesAddUpExactlyWithLargestRemainder() {
        // Same case as Sovereigncast's _split_recipients: 100 split 3 ways.
        val shares = Splits.divide(listOf(r("a", 1), r("b", 1), r("c", 1)), 100)
        assertEquals(mapOf("a" to 34L, "b" to 33L, "c" to 33L), amounts(shares))
    }

    @Test
    fun theNodesignalBlock() {
        val shares = Splits.divide(listOf(r("Nodesignal", 80), r("Karsten", 20)), 2_100_000)
        assertEquals(mapOf("Nodesignal" to 1_680_000L, "Karsten" to 420_000L), amounts(shares))
    }

    @Test
    fun feeRecipientsTakeTheirPercentageFirst() {
        // Podcast Index style: 1 % fee off the top, 99 % shared 50/50.
        val shares = Splits.divide(listOf(r("host", 50), r("cohost", 50), r("index", 1, fee = true)), 100_000)
        assertEquals(mapOf("host" to 49_500L, "cohost" to 49_500L, "index" to 1_000L), amounts(shares))
    }

    @Test
    fun aBlockOfOnlyFeeRecipientsIsDividedByWeight() {
        // Same case as Sovereigncast's test_a_block_of_only_fee_recipients_is_divided_by_weight.
        val shares = Splits.divide(listOf(r("a", 1, fee = true), r("b", 3, fee = true)), 100_000)
        assertEquals(mapOf("a" to 25_000L, "b" to 75_000L), amounts(shares))
    }

    @Test
    fun recipientsWithoutAddressOrSplitAreLeftOut() {
        val shares = Splits.divide(listOf(r("a", 50), r("b", 50, address = ""), r("c", 0)), 1000)
        assertEquals(mapOf("a" to 1000L), amounts(shares))
    }

    @Test
    fun insideATimeSplitTheGuestGetsTheRemotePercentage() {
        val block = ValueBlock("lightning", "keysend", recipients = listOf(r("show", 100)),
            timeSplits = listOf(ValueTimeSplit(60.0, 120.0, remotePercentage = 90, recipients = listOf(r("guest", 100)))))
        assertEquals(mapOf("show" to 10_000L, "guest" to 90_000L), amounts(Splits.divideAt(block, 100_000, 90.0)))
        assertEquals(mapOf("show" to 100_000L), amounts(Splits.divideAt(block, 100_000, 200.0)))
    }
}
