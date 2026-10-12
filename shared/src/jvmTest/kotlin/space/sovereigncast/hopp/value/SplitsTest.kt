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

    /** Device feedback 2026-10-08: 21 sats at 80/20 arrived as 16 + 4 — the
     *  fractions of 16.8 and 4.2 are not credited by custodial accounts. */
    @Test
    fun boostsAreSplitInWholeSatsAndAddUp() {
        val block = space.sovereigncast.hopp.model.ValueBlock("lightning", "keysend", recipients = listOf(
            space.sovereigncast.hopp.model.ValueRecipient("Nodesignal", "node", "02a", 80),
            space.sovereigncast.hopp.model.ValueRecipient("Karsten", "lnaddress", "k@a.test", 20),
        ))
        val shares = Splits.divideAtWholeSats(block, 21_000, null)
        assertEquals(listOf(17_000L, 4_000L), shares.map { it.amountMsat })
        assertEquals(21_000L, shares.sumOf { it.amountMsat })
    }

    /** Security report 2026-10-10, H-02: fees above 100 % together paid out more than the boost. */
    @Test
    fun feesTogetherNeverTakeMoreThanTheTotal() {
        assertEquals(mapOf("f1" to 500L, "f2" to 500L), amounts(Splits.divide(listOf(r("f1", 80, fee = true), r("f2", 80, fee = true), r("host", 50)), 1_000)))
        assertEquals(1_000L, Splits.divide(listOf(r("a", 100, true), r("b", 100, true), r("c", 100, true), r("host", 1)), 1_000).sumOf { it.amountMsat })
        // Below 100 % nothing changes: each fee its percentage, the rest by split.
        assertEquals(mapOf("f1" to 300L, "f2" to 200L, "host" to 500L), amounts(Splits.divide(listOf(r("f1", 30, true), r("f2", 20, true), r("host", 7)), 1_000)))
    }

    @Test
    fun hugeSplitsDoNotOverflow() {
        val shares = Splits.divide(listOf(r("a", Int.MAX_VALUE), r("b", Int.MAX_VALUE - 1), r("fee", Int.MAX_VALUE, true)), 100_000_000_000)
        assertEquals(100_000_000_000L, shares.sumOf { it.amountMsat })
        assertEquals(true, shares.all { it.amountMsat > 0 })
    }

    /** Any block a feed can write: the shares add up to the total, never more. */
    @Test
    fun noBlockPaysMoreThanTheTotal() {
        val rnd = kotlin.random.Random(20261010)
        repeat(5_000) {
            val recipients = List(rnd.nextInt(0, 8)) { i ->
                val split = when (rnd.nextInt(5)) { 0 -> rnd.nextInt(Int.MIN_VALUE, 0); 1 -> rnd.nextInt(); 2 -> 100; else -> rnd.nextInt(0, 120) }
                r("r$i", split, fee = rnd.nextBoolean())
            }
            val total = when (rnd.nextInt(3)) { 0 -> rnd.nextLong(0, 1_000); 1 -> rnd.nextLong(0, 100_000_000); else -> rnd.nextLong(0, 1_000_000_000_000) }
            val shares = Splits.divide(recipients, total)
            val sum = shares.sumOf { it.amountMsat }
            assertEquals(true, sum <= total && shares.all { it.amountMsat > 0 }, "$recipients / $total -> $shares")
            if (total > 0 && recipients.any { it.split > 0 }) assertEquals(total, sum, "$recipients / $total")
        }
    }
}
