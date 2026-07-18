package app.stockpickers.kmp.data.remote

import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Read-only client for Yahoo Finance's public chart endpoint
 * (`/v8/finance/chart/{symbol}`). One GET yields the daily close series plus the
 * latest quote and currency — no batching, no per-ticker loop.
 *
 * TWO NON-OBVIOUS RULES, both learned the hard way (see the investing pipeline's
 * `yfinance_access.md`):
 *  - A BROWSER User-Agent is MANDATORY. Yahoo answers 429 to the default Ktor
 *    agent; [BROWSER_UA] makes the request look like Chrome.
 *  - Yahoo rate-limits by IP. A 429 (or any non-2xx) on `query1` is retried once
 *    on `query2` — the two hosts throttle independently. This client never loops
 *    or hammers; freshness caching (6h, in the repository) is the real mitigation.
 *
 * The app's tickers are already Yahoo symbols (`DAVE`, `2330.TW`, `BPE.MI`,
 * `8411.T`, `005930.KS`), so no symbol mapping is needed.
 */
class YahooChartApi(
    private val client: HttpClient,
) {
    /**
     * Fetches [ticker]'s [range] daily history, or null when Yahoo has no data
     * for it (`chart.error` set / `result` absent) or every host refused. A thrown
     * exception (offline, timeout) is left to the caller — the repository catches
     * it and keeps whatever is cached.
     */
    suspend fun fetchChart(ticker: String, range: String = "6mo"): PriceSeries? {
        val body = fetchFrom(HOST_PRIMARY, ticker, range)
            ?: fetchFrom(HOST_FALLBACK, ticker, range)
            ?: return null
        val result = body.chart.result?.firstOrNull() ?: return null
        return result.toPriceSeries(ticker)
    }

    /**
     * Parsed body from [host], or null to let [fetchChart] fall back to the next
     * host / give up. A 429 lands here as a non-success status → null → fallback.
     */
    private suspend fun fetchFrom(host: String, ticker: String, range: String): ChartResponse? {
        val response: HttpResponse = client.get("https://$host$CHART_PATH$ticker") {
            header(HttpHeaders.UserAgent, BROWSER_UA)
            url.parameters.append("range", range)
            url.parameters.append("interval", INTERVAL)
        }
        return if (response.status.isSuccess()) response.body() else null
    }

    private companion object {
        const val HOST_PRIMARY = "query1.finance.yahoo.com"
        const val HOST_FALLBACK = "query2.finance.yahoo.com"
        const val CHART_PATH = "/v8/finance/chart/"
        const val INTERVAL = "1d"
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
    val closes = indicators?.quote?.firstOrNull()?.close.orEmpty()
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
private data class Indicators(val quote: List<Quote>? = null)

@Serializable
private data class Quote(
    // Nullable elements: Yahoo emits null for missing sessions. Kept null here and
    // filtered in [toPriceSeries] by pairing with the timestamp.
    val close: List<Double?>? = null,
)
