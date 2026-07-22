package app.stockpickers.kmp.ui

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pins the number formatting, because this file carries the project's most
 * dangerous convention and the failure mode is silent.
 *
 * `mom_*` and `roic` are DECIMAL FRACTIONS (0.55 == +55%) while `r2` and `peg` are
 * plain ratios. Reaching for [formatRatio] where [formatMomentum] belongs — or the
 * reverse — ships a number that is 100x off, with no crash, no exception and no
 * visual tell. Nothing else in the suite would notice.
 *
 * [format] is also hand-rolled: commonMain has no `String.format`, so the rounding,
 * the sign and the zero-padding are all this project's own code.
 */
class FormattingTest {

    // ---- Double.format: the hand-rolled fixed-decimal formatter ---------------

    @Test
    fun WHEN_decimals_is_zero_THEN_no_decimal_point_is_emitted() {
        12.4.format(0) shouldBe "12"
        12.5.format(0) shouldBe "13" // rounds half up
    }

    @Test
    fun WHEN_the_fraction_is_shorter_than_the_width_THEN_it_is_zero_padded() {
        0.5.format(2) shouldBe "0.50"
        1.2.format(2) shouldBe "1.20"
        3.0.format(3) shouldBe "3.000"
    }

    @Test
    fun WHEN_the_value_is_negative_THEN_the_sign_leads_the_magnitude() {
        (-1.25).format(2) shouldBe "-1.25"
        // Rounds to zero but keeps the sign — pinned deliberately: this is what the
        // UI shows for a tiny negative move, and either behaviour is defensible.
        (-0.0004).format(2) shouldBe "-0.00"
    }

    @Test
    fun WHEN_rounding_lands_on_a_boundary_THEN_it_carries_into_the_whole_part() {
        0.999.format(2) shouldBe "1.00"
        9.99.format(1) shouldBe "10.0"
    }

    // ---- fractions vs ratios: the convention this file exists to protect ------

    @Test
    fun WHEN_formatting_momentum_THEN_the_fraction_is_scaled_and_explicitly_signed() {
        formatMomentum(0.55) shouldBe "+55.0%"
        formatMomentum(0.08) shouldBe "+8.0%"
        formatMomentum(-0.123) shouldBe "-12.3%"
        formatMomentum(0.0) shouldBe "+0.0%"
    }

    /** ROIC is a fraction too, but it is a LEVEL, so it carries no '+'. */
    @Test
    fun WHEN_formatting_a_percent_level_THEN_it_is_scaled_but_unsigned() {
        formatPercent(0.18) shouldBe "18.0%"
        formatPercent(-0.05) shouldBe "-5.0%"
    }

    /** A ratio is unitless: never scaled, never suffixed with '%'. */
    @Test
    fun WHEN_formatting_a_ratio_THEN_it_is_neither_scaled_nor_suffixed() {
        formatRatio(1.8) shouldBe "1.80"
        formatRatio(0.95) shouldBe "0.95"
        // Narrowing the width rounds rather than truncates: 0.95 at one decimal is
        // "1.0", not "0.9".
        formatRatio(0.95, decimals = 1) shouldBe "1.0"
        formatRatio(0.94, decimals = 1) shouldBe "0.9"
    }

    /** clenow arrives ALREADY multiplied by 100 upstream — scaling it again is the bug. */
    @Test
    fun WHEN_formatting_clenow_THEN_it_is_passed_through_unscaled() {
        formatClenow(45.0) shouldBe "45.00"
        formatClenow(4400.0) shouldBe "4400.00"
    }

    // ---- quotes: symbol placement and the sign-before-symbol rule -------------

    @Test
    fun WHEN_a_currency_has_a_symbol_THEN_it_leads_the_amount() {
        formatQuote(150.0, "USD") shouldBe "$150.00"
        formatQuote(93.2, "EUR") shouldBe "€93.20"
    }

    @Test
    fun WHEN_a_currency_has_no_widely_read_symbol_THEN_the_code_trails_the_amount() {
        formatQuote(35.55, "TWD") shouldBe "35.55 TWD"
    }

    @Test
    fun WHEN_the_currency_is_unknown_THEN_the_bare_amount_is_shown() {
        formatQuote(35.55, null) shouldBe "35.55"
    }

    /** The sign leads the symbol: `-$3.95`, never `$-3.95`. */
    @Test
    fun WHEN_formatting_a_signed_quote_THEN_the_sign_precedes_the_symbol() {
        formatSignedQuote(12.34, "USD") shouldBe "+$12.34"
        formatSignedQuote(-3.95, "USD") shouldBe "-$3.95"
        formatSignedQuote(-5.1, "TWD") shouldBe "-5.10 TWD"
    }

    @Test
    fun WHEN_formatting_a_signed_percent_THEN_the_fraction_is_scaled_and_signed() {
        formatSignedPercent(0.021) shouldBe "+2.10%"
        formatSignedPercent(-0.05) shouldBe "-5.00%"
    }

    @Test
    fun WHEN_formatting_a_price_in_euro_THEN_the_symbol_leads() {
        formatPriceEur(12.5) shouldBe "€12.50"
    }

    // ---- the null contract ---------------------------------------------------

    /**
     * Every field upstream except `ticker` is nullable, and `price_eur` is currently
     * null for EVERY row — so the em dash is the most-rendered output in the app.
     */
    @Test
    fun WHEN_the_value_is_null_THEN_every_formatter_renders_an_em_dash() {
        formatMomentum(null) shouldBe "—"
        formatPercent(null) shouldBe "—"
        formatRatio(null) shouldBe "—"
        formatClenow(null) shouldBe "—"
        formatPriceEur(null) shouldBe "—"
        formatQuote(null, "USD") shouldBe "—"
        formatSignedQuote(null, "USD") shouldBe "—"
        formatSignedPercent(null) shouldBe "—"
    }
}
