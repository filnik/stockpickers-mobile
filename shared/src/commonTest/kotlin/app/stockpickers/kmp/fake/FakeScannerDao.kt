package app.stockpickers.kmp.fake

import app.stockpickers.kmp.data.local.GeoCountsRow
import app.stockpickers.kmp.data.local.PriceSeriesEntity
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import app.stockpickers.kmp.data.local.TickerProfileEntity
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
    // Keyed by the composite PK (ticker, rangeKey) — one entry per (ticker, range),
    // mirroring the real `price_series` table.
    val priceSeries = MutableStateFlow<Map<Pair<String, String>, PriceSeriesEntity>>(emptyMap())
    val profiles = MutableStateFlow<Map<String, TickerProfileEntity>>(emptyMap())
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

    override fun observePriceSeries(ticker: String, rangeKey: String): Flow<PriceSeriesEntity?> =
        priceSeries.map { it[ticker to rangeKey] }

    override suspend fun upsertPriceSeries(series: PriceSeriesEntity) {
        priceSeries.value = priceSeries.value + ((series.ticker to series.rangeKey) to series)
    }

    override suspend fun getPriceSeriesFetchedAt(ticker: String, rangeKey: String): Long? =
        priceSeries.value[ticker to rangeKey]?.fetchedAt

    override fun observeProfile(ticker: String): Flow<TickerProfileEntity?> =
        profiles.map { it[ticker] }

    override suspend fun upsertProfile(profile: TickerProfileEntity) {
        profiles.value = profiles.value + (profile.ticker to profile)
    }

    override suspend fun getProfileFetchedAt(ticker: String): Long? =
        profiles.value[ticker]?.fetchedAt
}
