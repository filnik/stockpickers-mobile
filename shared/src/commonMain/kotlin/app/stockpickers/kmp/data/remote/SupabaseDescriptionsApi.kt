package app.stockpickers.kmp.data.remote

import app.stockpickers.kmp.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import org.koin.core.annotation.Single

/**
 * Read-only client for the `descriptions_cache` PostgREST endpoint — one row by
 * ticker, for the detail screen's profile card.
 *
 * Separate from [SupabaseScannerApi] even though both hit the same project: that one
 * is built around `scanner_cache`'s 1000-row pagination contract, this is a single
 * primary-key lookup. They share authentication and nothing else.
 *
 * WHY NOT `Accept: application/vnd.pgrst.object+json` (the wire form of the web
 * client's `.maybeSingle()`): that header makes PostgREST answer **406** when the
 * result is empty. Here empty is the MAJORITY case — most tickers have no profile —
 * so it would turn the normal outcome into an HTTP error. We ask for an array and
 * take the first element.
 *
 * The auth headers are set per-request rather than on the client. The [HttpClient] is
 * a shared singleton that also talks to Yahoo Finance, so a `defaultRequest` block
 * would send the Supabase key to a third party.
 */
@Single
class SupabaseDescriptionsApi(
    private val client: HttpClient,
    private val baseUrl: String = SupabaseConfig.URL,
    private val anonKey: String = SupabaseConfig.ANON_KEY,
) {
    /** The profile row for [ticker], or null when upstream has none. */
    suspend fun fetchProfile(ticker: String): DescriptionsRowDto? {
        val response: HttpResponse = client.get("$baseUrl/rest/v1/$TABLE") {
            url.parameters.append("select", SELECT)
            // Keys are stored uppercase upstream; the web client uppercases too.
            url.parameters.append("ticker", "eq.${ticker.uppercase()}")
            url.parameters.append("limit", "1")
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
        }
        if (!response.status.isSuccess()) {
            error("Supabase returned ${response.status.value} for profile $ticker")
        }
        return response.body<List<DescriptionsRowDto>>().firstOrNull()
    }

    private companion object {
        const val TABLE = "descriptions_cache"

        /**
         * Only the columns the card renders. `country`/`sector` are skipped on
         * purpose — they already come from `scanner_cache`, and having two rows able
         * to disagree about a ticker's sector would be a bug waiting to happen.
         */
        const val SELECT = "ticker,timeless,current"
    }
}
