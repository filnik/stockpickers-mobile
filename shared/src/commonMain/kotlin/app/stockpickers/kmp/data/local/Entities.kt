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
    val wyckoffMarkdown: Boolean?,
    val duplicateOf: String?,
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
