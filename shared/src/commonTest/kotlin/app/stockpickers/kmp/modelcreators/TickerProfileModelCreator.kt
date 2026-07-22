package app.stockpickers.kmp.modelcreators

import app.stockpickers.kmp.base.SingleModelCreator
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.TickerProfile

/**
 * The written profile shown on the detail screen.
 *
 * [model] is deliberately COMPLETE — every block filled, both freshness verdicts
 * FRESH — so a test carves out exactly the one thing it is about with `copy`. The
 * common real-world case is the opposite (most tickers have no profile at all), and
 * that case is `null`, not a sparse instance of this.
 */
object TickerProfileModelCreator : SingleModelCreator<TickerProfile> {

    override val model = TickerProfile(
        ticker = "AAA",
        timelessDescription = "Alpha Corp designs and sells widgets.",
        currentDescription = "The latest quarter beat expectations on services.",
        pros = listOf("Expanding margins", "Large installed base"),
        cons = listOf("Supply-chain concentration", "Growth stalling in mature markets"),
        nextEarnings = NextEarningsModelCreator.model,
        timelessFreshness = ContentFreshness.FRESH,
        currentFreshness = ContentFreshness.FRESH,
    )
}

/**
 * Upstream's `next_earnings` block.
 *
 * Note `consensus` is prose, not a rating — the name is upstream's and is
 * misleading. `daysAway` is always RECOMPUTED from `date` by the repository and
 * never replayed from the payload, so a creator value here is only a stand-in for
 * the already-computed result.
 */
object NextEarningsModelCreator : SingleModelCreator<NextEarnings> {

    override val model = NextEarnings(
        date = "2026-08-05",
        daysAway = 12,
        consensus = "Q3 results on 5 August. Watch services margins and AI capex commentary.",
    )
}
