package app.stockpickers.kmp.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Two invariants that are easy to break by editing the enum table and impossible to
 * notice afterwards.
 */
class ChartRangeTest {

    /**
     * `rangeKey` is part of the Room `price_series` COMPOSITE PRIMARY KEY. Deriving
     * it from anything but `name` — or renaming a constant — silently orphans every
     * cached series: no error, just a chart that reloads from the network forever.
     */
    @Test
    fun WHEN_reading_the_cache_key_THEN_it_is_the_enum_name() {
        ChartRange.entries.forEach { range ->
            withClue("${range.name}'s cache key drifted from its enum name") {
                range.rangeKey shouldBe range.name
            }
        }
    }

    /**
     * `isIntraday` is a STRING COMPARISON against "1d". It selects the freshness TTL
     * — roughly five minutes versus six hours — so a new interval token like "1wk"
     * would be classified intraday and re-fetch about seventy times more often, with
     * nothing to signal it.
     */
    @Test
    fun WHEN_the_interval_is_daily_THEN_the_range_is_not_intraday() {
        ChartRange.ONE_DAY.isIntraday shouldBe true
        ChartRange.ONE_WEEK.isIntraday shouldBe true

        ChartRange.ONE_MONTH.isIntraday shouldBe false
        ChartRange.THREE_MONTHS.isIntraday shouldBe false
        ChartRange.SIX_MONTHS.isIntraday shouldBe false
        ChartRange.ONE_YEAR.isIntraday shouldBe false
    }

    /** Every non-intraday range must share one interval — that is what lets ONE 1Y fetch warm all four. */
    @Test
    fun WHEN_a_range_is_not_intraday_THEN_it_uses_the_daily_interval() {
        ChartRange.entries.filterNot { it.isIntraday }.forEach { range ->
            withClue("${range.name} is daily but does not request the 1d interval") {
                range.yahooInterval shouldBe "1d"
            }
        }
    }

    @Test
    fun WHEN_the_chart_opens_THEN_the_default_range_is_six_months() {
        ChartRange.DEFAULT shouldBe ChartRange.SIX_MONTHS
        ChartRange.DEFAULT.isIntraday shouldBe false // opening on an intraday range would burn the short TTL
    }
}
