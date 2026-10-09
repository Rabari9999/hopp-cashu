package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.model.ValueTimeSplit

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

    /** The value time split that applies at [positionSeconds], if any. */
    fun activeSplit(block: ValueBlock, positionSeconds: Double?): ValueTimeSplit? = positionSeconds?.let { pos ->
        block.timeSplits.firstOrNull { pos >= it.startTime && pos < it.startTime + it.duration && (it.recipients.isNotEmpty() || it.remoteItem != null) }
    }

    /**
     * The shares at a playback position: inside a time split,
     * `remotePercentage` (default 100) goes to its recipients and the rest to
     * the main block. A split pointing at another feed (`remoteItem` — music
     * from Wavlake, say) needs that feed's value block, resolved by the caller
     * as [remote]. Unresolved, the share stays as one "remote" recipient that
     * is skipped and shown, not handed to the others (ADR 0005).
     */
    fun divideAt(block: ValueBlock, totalMsat: Long, positionSeconds: Double?, remote: ValueBlock? = null): List<Share> {
        val active = activeSplit(block, positionSeconds) ?: return divide(block.recipients, totalMsat)
        val percent = (active.remotePercentage ?: 100).coerceIn(0, 100)
        val remoteMsat = totalMsat * percent / 100
        val main = divide(block.recipients, totalMsat - remoteMsat)
        val targets = active.recipients.ifEmpty { remote?.recipients.orEmpty() }
        if (targets.isNotEmpty()) return main + divide(targets, remoteMsat)
        if (remoteMsat <= 0) return main
        val placeholder = ValueRecipient(name = active.remoteItem?.medium ?: "remote", type = REMOTE, address = active.remoteItem?.feedGuid ?: "", split = 100)
        return main + Share(placeholder, remoteMsat)
    }

    /**
     * As [divideAt], but in whole sats (device feedback 2026-10-08): 21 sats at
     * 80/20 are 17 + 4, not 16.8 + 4.2 — custodial accounts (Alby's, say) credit
     * whole sats only, so the fractions never arrived and a 21-sat boost showed
     * up as 20. The largest remainders still decide who gets the odd sat; the
     * total is exactly what the listener chose, and routing fees come on top.
     */
    fun divideAtWholeSats(block: ValueBlock, totalMsat: Long, positionSeconds: Double?, remote: ValueBlock? = null): List<Share> =
        divideAt(block, totalMsat / 1000, positionSeconds, remote).map { it.copy(amountMsat = it.amountMsat * 1000) }

    fun divideWholeSats(recipients: List<ValueRecipient>, totalMsat: Long): List<Share> =
        divide(recipients, totalMsat / 1000).map { it.copy(amountMsat = it.amountMsat * 1000) }

    /** Recipient type of an unresolved remote item's share. */
    const val REMOTE = "remote"

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
