package app.stockpickers.kmp.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The two derived figures beside the chart. Real arithmetic with a real division
 * hazard, and every bound is nullable because upstream's fields are.
 */
class PriceSeriesTest {

    private fun series(last: Double?, previousClose: Double?) = PriceSeries(
        ticker = "NVDA",
        currency = "USD",
        last = last,
        previousClose = previousClose,
        points = emptyList(),
    )

    @Test
    fun WHEN_both_bounds_are_present_THEN_the_period_change_is_their_difference() {
        val s = series(last = 110.0, previousClose = 100.0)

        s.periodChange shouldBe 10.0
        // A DECIMAL FRACTION, like mom_* — the UI multiplies by 100.
        s.periodChangePercent shouldBe 0.1
    }

    @Test
    fun WHEN_the_period_is_negative_THEN_both_figures_are_negative() {
        val s = series(last = 90.0, previousClose = 100.0)

        s.periodChange shouldBe -10.0
        s.periodChangePercent shouldBe -0.1
    }

    @Test
    fun WHEN_the_last_price_is_missing_THEN_both_figures_are_null() {
        val s = series(last = null, previousClose = 100.0)

        s.periodChange.shouldBeNull()
        s.periodChangePercent.shouldBeNull()
    }

    @Test
    fun WHEN_the_previous_close_is_missing_THEN_both_figures_are_null() {
        val s = series(last = 110.0, previousClose = null)

        s.periodChange.shouldBeNull()
        s.periodChangePercent.shouldBeNull()
    }

    /**
     * The divide-by-zero guard. The absolute change is still meaningful with a zero
     * base; the percentage is not, and must be null rather than Infinity or NaN —
     * either of which would reach the formatter and render as garbage.
     */
    @Test
    fun WHEN_the_previous_close_is_zero_THEN_only_the_percentage_is_suppressed() {
        val s = series(last = 110.0, previousClose = 0.0)

        s.periodChange shouldBe 110.0
        s.periodChangePercent.shouldBeNull()
    }
}
