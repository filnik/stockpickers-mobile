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
 * A non-2xx answer from PostgREST. Typed rather than a bare [error] so the caller
 * can tell "the server refused" from "we never reached one" — an IOException — and
 * report the right thing to the user.
 */
class SupabaseHttpException(val status: Int, message: String) : Exception(message)

/**
 * Read-only client for the `scanner_cache` PostgREST endpoint.
 *
 * NOTE ON PAGINATION: Supabase caps a single response at 1000 rows server-side,
 * regardless of the `limit` in the query string (the table currently holds ~1804
 * rows). A naive single GET therefore silently truncates the universe and
 * corrupts the leaders board. We page with the `Range` header until the server
 * returns a short page.
 */
@Single
class SupabaseScannerApi(
    private val client: HttpClient,
    private val baseUrl: String = SupabaseConfig.URL,
    private val anonKey: String = SupabaseConfig.ANON_KEY,
) {
    suspend fun fetchScannerCache(): List<TickerDto> {
        val all = mutableListOf<TickerDto>()
        var offset = 0
        while (true) {
            val page = fetchPage(offset, offset + PAGE_SIZE - 1)
            all += page
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
            // Hard stop, and it must THROW rather than return what it has. The caller
            // prunes the local cache against this list, so a silently truncated
            // universe would delete every row past the cap. Returning partial data is
            // never safe here: an incomplete answer is a failed answer.
            if (offset >= MAX_ROWS) {
                throw SupabaseHttpException(
                    status = 0,
                    message = "scanner_cache exceeded $MAX_ROWS rows; refusing to sync a truncated universe",
                )
            }
        }
        return all
    }

    private suspend fun fetchPage(from: Int, to: Int): List<TickerDto> {
        val response: HttpResponse = client.get("$baseUrl/rest/v1/$TABLE") {
            url.parameters.append("select", SELECT)
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("Range-Unit", "items")
            header("Range", "$from-$to")
        }
        if (!response.status.isSuccess()) {
            throw SupabaseHttpException(
                status = response.status.value,
                message = "Supabase returned ${response.status.value} for rows $from-$to",
            )
        }
        return response.body()
    }

    private companion object {
        const val TABLE = "scanner_cache"
        const val PAGE_SIZE = 1000
        const val MAX_ROWS = 10_000

        /** Flattens the `data` JSONB payload onto the row. Keep in sync with the web client. */
        const val SELECT = "ticker,name,country,sector,price_eur,updated_at," +
            "clenow:data->clenow,mom_1m:data->mom_1m,mom_2m:data->mom_2m," +
            "mom_3m:data->mom_3m,mom_12m:data->mom_12m,ann_mom:data->ann_mom," +
            "forward_pe:data->forward_pe," +
            "peg:data->peg,roic:data->roic,r2:data->r2," +
            "quality_gate:data->quality_gate,wyckoff_markdown:data->wyckoff_markdown," +
            "duplicate_of:data->duplicate_of"
    }
}
