package app.stockpickers.kmp.data.remote

import app.stockpickers.kmp.data.remote.YahooChartApi.Companion.BROWSER_UA
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/**
 * Read-only client for Yahoo Finance's public chart endpoint
 * (`/v8/finance/chart/{symbol}`). One GET yields the daily close series plus the
 * latest quote and currency — no batching, no per-ticker loop.
 *
 * TWO NON-OBVIOUS RULES, both learned the hard way:
 *  - The endpoint expects a browser User-Agent and answers 429 to Ktor's default
 *    one, so [BROWSER_UA] supplies a standard desktop agent string.
 *  - Yahoo throttles by IP. A 429 (or any non-2xx) on `query1` is retried once on
 *    `query2` — the two hosts throttle independently. This client never loops or
 *    hammers: it fetches one symbol when the user opens a chart, and freshness
 *    caching (6h daily / 5min intraday, in the repository) is the real mitigation.
 *
 * The endpoint is public but undocumented and unofficial, and this is a personal-
 * scale client. Anything wider should front it with a proper data source.
 *
 * The app's tickers are already Yahoo symbols (`DAVE`, `2330.TW`, `BPE.MI`,
 * `8411.T`, `005930.KS`), so no symbol mapping is needed.
 */
@Single
class YahooChartApi(private val client: HttpClient) {
    /**
     * Fetches [ticker]'s history over [range] at candle size [interval], or null
     * when Yahoo has no data for it (`chart.error` set / `result` absent) or every
     * host refused. A thrown exception (offline, timeout) is left to the caller —
     * the repository catches it and keeps whatever is cached.
     *
     * [range]/[interval] are Yahoo's own tokens (e.g. `6mo`/`1d`, `1d`/`5m`). The
     * caller pairs them from [app.stockpickers.kmp.domain.ChartRange]; an
     * incompatible pair just yields no data (→ null), never a crash.
     */
    suspend fun fetchChart(ticker: String, range: String = "6mo", interval: String = "1d"): PriceSeries? {
        val body = fetchFrom(HOST_PRIMARY, ticker, range, interval)
            ?: fetchFrom(HOST_FALLBACK, ticker, range, interval)
            ?: return null
        val result = body.chart.result?.firstOrNull() ?: return null
        return result.toPriceSeries(ticker)
    }

    /**
     * Parsed body from [host], or null to let [fetchChart] fall back to the next
     * host / give up. A 429 lands here as a non-success status → null → fallback.
     */
    private suspend fun fetchFrom(host: String, ticker: String, range: String, interval: String): ChartResponse? {
        val response: HttpResponse = client.get("https://$host$CHART_PATH$ticker") {
            header(HttpHeaders.UserAgent, BROWSER_UA)
            url.parameters.append("range", range)
            url.parameters.append("interval", interval)
        }
        return if (response.status.isSuccess()) response.body() else null
    }

    private companion object {
        const val HOST_PRIMARY = "query1.finance.yahoo.com"
        const val HOST_FALLBACK = "query2.finance.yahoo.com"
        const val CHART_PATH = "/v8/finance/chart/"
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/**
 * Pairs Yahoo's parallel `timestamp` / `close` arrays into clean points. Closes
 * are nullable upstream (holidays, halts) and are dropped by zipping against
 * their timestamp — never index-shifted, so a gap can't misalign the series.
 */
private fun ChartResult.toPriceSeries(ticker: String): PriceSeries {
    val timestamps = timestamp.orEmpty()
    // ADJUSTED closes win when present. The raw `quote.close` series is NOT adjusted
    // for splits or dividends, so a 10:1 split draws a cliff that never happened —
    // and, worse, the chart then disagrees with the mom_*/clenow figures shown on the
    // same screen, which upstream computes on adjusted prices. `adjclose` is daily-
    // only, so intraday ranges fall back to the raw quote (where, over hours, no
    // corporate action can have intervened anyway).
    val adjusted = indicators?.adjclose?.firstOrNull()?.adjclose.orEmpty()
    val closes = adjusted.ifEmpty { indicators?.quote?.firstOrNull()?.close.orEmpty() }
    val points = timestamps.zip(closes)
        .mapNotNull { (ts, close) -> close?.let { PricePoint(epochSeconds = ts, close = it) } }
    return PriceSeries(
        // Key by the REQUESTED ticker (the Room PK), not meta.symbol, so the cache
        // round-trips: meta.symbol can differ in case/suffix from what we asked for.
        ticker = ticker,
        currency = meta?.currency,
        last = meta?.regularMarketPrice,
        previousClose = meta?.chartPreviousClose,
        points = points,
    )
}

// --- DTOs: only the fields we read. The shared Json has ignoreUnknownKeys. ---

@Serializable
private data class ChartResponse(val chart: Chart)

@Serializable
private data class Chart(
    val result: List<ChartResult>? = null,
    // Present (non-null) exactly when Yahoo has no data for the symbol; `result`
    // is null in that case, so `result.firstOrNull()` already handles it. Declared
    // for clarity, not consumed.
    val error: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
private data class ChartResult(
    val meta: Meta? = null,
    val timestamp: List<Long>? = null,
    val indicators: Indicators? = null,
)

@Serializable
private data class Meta(
    val currency: String? = null,
    val symbol: String? = null,
    val shortName: String? = null,
    val regularMarketPrice: Double? = null,
    val chartPreviousClose: Double? = null,
)

@Serializable
private data class Indicators(
    val quote: List<Quote>? = null,
    /**
     * Split- and dividend-adjusted closes. Yahoo returns this for DAILY intervals
     * only — intraday responses have no `adjclose` block at all, which is why
     * [toPriceSeries] falls back to the raw quote rather than requiring it.
     */
    val adjclose: List<AdjClose>? = null,
)

@Serializable
private data class Quote(
    // Nullable elements: Yahoo emits null for missing sessions. Kept null here and
    // filtered in [toPriceSeries] by pairing with the timestamp.
    val close: List<Double?>? = null,
)

@Serializable
private data class AdjClose(val adjclose: List<Double?>? = null)
