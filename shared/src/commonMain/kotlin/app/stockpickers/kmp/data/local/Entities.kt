package app.stockpickers.kmp.data.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The cached scanner universe. Room is the single source of truth: the UI reads
 * this table and nothing else.
 *
 * `qualityPasses` is the FLATTENED `quality_gate.passes_filters` verdict. It is
 * deliberately nullable so the DAO can apply the fail-safe rule (a row whose
 * verdict is missing must be excluded, not assumed good).
 *
 * `qualityReason` / `qualityFailedFilter` are the other two flattened members of
 * the same upstream `quality_gate` object. They carry no filtering logic — the
 * detail screen shows them so a user can see WHY a row was rejected.
 */
@Entity(tableName = "tickers")
data class TickerEntity(
    @PrimaryKey val ticker: String,
    val name: String?,
    val country: String?,
    val sector: String?,
    val priceEur: Double?,
    val updatedAt: String?,
    val clenow: Double?,
    val mom1m: Double?,
    val mom2m: Double?,
    val mom3m: Double?,
    val mom12m: Double?,
    val forwardPe: Double?,
    val peg: Double?,
    val roic: Double?,
    val r2: Double?,
    val qualityPasses: Boolean?,
    val qualityReason: String?,
    val qualityFailedFilter: String?,
    val wyckoffMarkdown: Boolean?,
    val duplicateOf: String?,
)

/**
 * Cached Yahoo Finance price history for one ticker, backing the detail chart.
 * Offline-first, exactly like [TickerEntity]: the UI observes this table and the
 * network only ever writes into it.
 *
 * [pointsJson] is a `List<PricePoint>` serialized with kotlinx (Room stores no
 * lists natively and this cache is disposable, so a JSON column is simpler than a
 * join table). [fetchedAt] is the local wall-clock (epoch millis) of the fetch,
 * used to skip refetching while the cache is still fresh (~6h).
 */
@Entity(tableName = "price_series")
data class PriceSeriesEntity(
    @PrimaryKey val ticker: String,
    val currency: String?,
    val last: Double?,
    val previousClose: Double?,
    val pointsJson: String,
    val fetchedAt: Long,
)

/** Single-row table holding sync bookkeeping (id is always [SYNC_ID]). */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val id: Int = SYNC_ID,
    /** Epoch millis of the last SUCCESSFUL sync. */
    val lastSyncedAt: Long,
) {
    companion object {
        const val SYNC_ID = 0
    }
}
