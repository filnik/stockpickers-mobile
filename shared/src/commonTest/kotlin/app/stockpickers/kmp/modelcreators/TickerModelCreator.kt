package app.stockpickers.kmp.modelcreators

import app.stockpickers.kmp.base.MultipleModelsCreator
import app.stockpickers.kmp.domain.Ticker

/** Domain [Ticker] projection — what the leaders board renders. */
object TickerModelCreator : MultipleModelsCreator<Ticker> {

    override val model = Ticker(
        ticker = "AAA",
        name = "Alpha Corp",
        country = "United States",
        sector = "Technology",
        priceEur = null,
        clenow = 1.0,
        mom1m = 0.10,
        mom2m = 0.20,
        mom3m = 0.30,
        mom12m = 0.50,
        forwardPe = 20.0,
        peg = 1.5,
        roic = 0.25,
        r2 = 0.90,
    )

    override fun list(count: Int): List<Ticker> = (0 until count).map { i ->
        model.copy(ticker = "T$i", name = "Ticker $i", clenow = (count - i).toDouble())
    }
}
