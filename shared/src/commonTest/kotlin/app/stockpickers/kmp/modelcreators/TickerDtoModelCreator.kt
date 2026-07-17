package app.stockpickers.kmp.modelcreators

import app.stockpickers.kmp.base.MultipleModelsCreator
import app.stockpickers.kmp.data.remote.TickerDto

/** Network DTO — the shape the fake Supabase transport returns. */
object TickerDtoModelCreator : MultipleModelsCreator<TickerDto> {

    override val model = TickerDto(
        ticker = "AAA",
        name = "Alpha Corp",
        country = "United States",
        sector = "Technology",
        clenow = 1.0,
        mom1m = 0.10,
        mom2m = 0.20,
        mom3m = 0.30,
    )

    override fun list(count: Int): List<TickerDto> = (0 until count).map { i ->
        model.copy(ticker = "T$i", name = "Ticker $i", clenow = (count - i).toDouble())
    }
}
