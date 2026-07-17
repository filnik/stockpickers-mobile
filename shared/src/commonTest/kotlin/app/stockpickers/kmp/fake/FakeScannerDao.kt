package app.stockpickers.kmp.fake

import app.stockpickers.kmp.data.local.GeoCountsRow
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ScannerDao] for the repository test. Hand-written, not MockK: MockK
 * is JVM-only and this file lives in commonTest (it must also compile for iOS).
 *
 * It deliberately does NOT replicate the qualifying/sorting SQL — that logic is
 * Room's and is covered by the real-database `ScannerDaoTest` in androidHostTest.
 * Here `observeMomentumLeaders` just re-emits the stored rows, so the repository
 * test can prove upsert/cache-survival semantics without a SQLite engine.
 */
class FakeScannerDao : ScannerDao {

    val rows = MutableStateFlow<List<TickerEntity>>(emptyList())
    private val syncMetadata = MutableStateFlow<SyncMetadataEntity?>(null)

    override fun observeMomentumLeaders(sort: String, geo: String, limit: Int): Flow<List<TickerEntity>> =
        rows.map { it.take(limit) }

    override fun observeGeoCounts(sort: String): Flow<GeoCountsRow> =
        rows.map { list -> GeoCountsRow(total = list.size, usa = list.size, ita = 0, asia = 0) }

    override fun observeTicker(ticker: String): Flow<TickerEntity?> =
        rows.map { list -> list.firstOrNull { it.ticker == ticker } }

    override suspend fun upsertAll(tickers: List<TickerEntity>) {
        val byKey = rows.value.associateBy { it.ticker }.toMutableMap()
        tickers.forEach { byKey[it.ticker] = it }
        rows.value = byKey.values.toList()
    }

    override suspend fun count(): Int = rows.value.size

    override suspend fun setSyncMetadata(metadata: SyncMetadataEntity) {
        syncMetadata.value = metadata
    }

    override fun observeLastSyncedAt(): Flow<Long?> = syncMetadata.map { it?.lastSyncedAt }
}
