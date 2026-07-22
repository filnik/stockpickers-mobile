package app.stockpickers.kmp.modelcreators

import app.stockpickers.kmp.base.MultipleModelsCreator
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries

/**
 * A cached chart series.
 *
 * [model] rises from 100 to 110 against a `previousClose` of 100, so the derived
 * figures come out to round numbers a test can assert without tolerance:
 * `periodChange == 10.0` and `periodChangePercent == 0.1`.
 */
object PriceSeriesModelCreator : MultipleModelsCreator<PriceSeries> {

    override val model = PriceSeries(
        ticker = "AAA",
        currency = "USD",
        last = 110.0,
        previousClose = 100.0,
        points = PricePointModelCreator.list(2),
    )

    /** Distinct tickers, so a test can tell two cached series apart. */
    override fun list(count: Int): List<PriceSeries> = (0 until count).map { i ->
        model.copy(ticker = "T$i")
    }
}

object PricePointModelCreator : MultipleModelsCreator<PricePoint> {

    override val model = PricePoint(epochSeconds = BASE_EPOCH, close = 105.0)

    /** One point per day, rising — ordering is assertable and gaps are not implied. */
    override fun list(count: Int): List<PricePoint> = (0 until count).map { i ->
        PricePoint(
            epochSeconds = BASE_EPOCH + i * SECONDS_PER_DAY,
            close = 105.0 + i * 5.0,
        )
    }
}

private const val BASE_EPOCH = 1_700_000_000L
private const val SECONDS_PER_DAY = 86_400L
