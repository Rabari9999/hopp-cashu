package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient

/** One recipient's share of a payment, in millisatoshis. */
data class Share(val recipient: ValueRecipient, val amountMsat: Long)

/**
 * How a payment is divided among the recipients of a value block.
 *
 * Fee recipients (`fee="true"`) take their split as a **percentage of the
 * total** first; the rest is divided among the others in proportion to their
 * splits (Podcasting 2.0 value spec). Integer amounts with largest-remainder
 * rounding, so the parts add up to exactly the total — the same rounding as
 * Sovereigncast's `_split_recipients` (ties keep feed order). Shares that
 * round to zero are dropped.
 */
object Splits {

    fun divide(recipients: List<ValueRecipient>, totalMsat: Long): List<Share> {
        if (totalMsat <= 0) return emptyList()
        val payable = recipients.filter { it.address.isNotBlank() && it.split > 0 }
        // A block of nothing but fee recipients has no rest to share out:
        // divide it by weight, as Sovereigncast does.
        if (payable.none { !it.fee }) {
            return largestRemainder(payable, totalMsat).filter { it.second > 0 }.map { Share(it.first, it.second) }
        }
        val fees = payable.filter { it.fee }
        val shares = payable.filterNot { it.fee }

        val feeAmounts = fees.map { r -> r to totalMsat * r.split.coerceAtMost(100) / 100 }
        val feeTotal = feeAmounts.sumOf { it.second }.coerceAtMost(totalMsat)
        val rest = largestRemainder(shares, totalMsat - feeTotal)
        return (feeAmounts + rest).filter { it.second > 0 }.map { Share(it.first, it.second) }
    }

    /**
     * The shares at a playback position: inside a time split with inline
     * recipients, `remotePercentage` (default 100) goes to them and the rest
     * to the main block. Remote items (another feed's value block) are not
     * resolved here — the caller has to fetch that block first.
     */
    fun divideAt(block: ValueBlock, totalMsat: Long, positionSeconds: Double?): List<Share> {
        val active = positionSeconds?.let { pos ->
            block.timeSplits.firstOrNull { pos >= it.startTime && pos < it.startTime + it.duration && it.recipients.isNotEmpty() }
        } ?: return divide(block.recipients, totalMsat)
        val percent = (active.remotePercentage ?: 100).coerceIn(0, 100)
        val remote = totalMsat * percent / 100
        return divide(block.recipients, totalMsat - remote) + divide(active.recipients, remote)
    }

    private fun largestRemainder(recipients: List<ValueRecipient>, amount: Long): List<Pair<ValueRecipient, Long>> {
        val totalWeight = recipients.sumOf { it.split.toLong() }
        if (totalWeight <= 0 || amount <= 0) return emptyList()
        val floors = recipients.map { amount * it.split / totalWeight }.toLongArray()
        val remainders = recipients.map { amount * it.split % totalWeight }
        var left = amount - floors.sum()
        val order = recipients.indices.sortedByDescending { remainders[it] }
        var k = 0
        while (left > 0) { floors[order[k % order.size]]++; left--; k++ }
        return recipients.mapIndexed { i, r -> r to floors[i] }
    }
}
