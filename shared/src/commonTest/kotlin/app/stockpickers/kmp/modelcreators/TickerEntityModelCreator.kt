package app.stockpickers.kmp.modelcreators

import app.stockpickers.kmp.base.MultipleModelsCreator
import app.stockpickers.kmp.data.local.TickerEntity

/**
 * A fully-QUALIFYING scanner row: US country, positive clenow, every window set,
 * quality gate passed, not a Wyckoff markdown, not a duplicate. Tests carve out
 * the edge cases (`.model.copy(qualityPasses = null)`, wrong country, etc.) from
 * this baseline so each test states only the field it is exercising.
 */
object TickerEntityModelCreator : MultipleModelsCreator<TickerEntity> {

    override val model = TickerEntity(
        ticker = "AAA",
        name = "Alpha Corp",
        country = "United States",
        sector = "Technology",
        priceEur = null,
        updatedAt = "2026-01-01T00:00:00Z",
        clenow = 1.0,
        mom1m = 0.10,
        mom2m = 0.20,
        mom3m = 0.30,
        mom12m = 0.50,
        forwardPe = 20.0,
        peg = 1.5,
        roic = 0.25,
        r2 = 0.90,
        qualityPasses = true,
        qualityReason = null,
        qualityFailedFilter = null,
        wyckoffMarkdown = false,
        duplicateOf = null,
    )

    /** Distinct tickers, clenow descending with the index so ordering is testable. */
    override fun list(count: Int): List<TickerEntity> = (0 until count).map { i ->
        model.copy(
            ticker = "T$i",
            name = "Ticker $i",
            clenow = (count - i).toDouble(),
        )
    }
}
