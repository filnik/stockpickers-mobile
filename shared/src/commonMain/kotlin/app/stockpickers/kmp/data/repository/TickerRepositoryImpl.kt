package app.stockpickers.kmp.data.repository

import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.SyncMetadataEntity
import app.stockpickers.kmp.data.local.TickerEntity
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.remote.TickerDto
import app.stockpickers.kmp.domain.MomentumWindow
import app.stockpickers.kmp.domain.RefreshResult
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class TickerRepositoryImpl(
    private val api: SupabaseScannerApi,
    private val dao: ScannerDao,
) : TickerRepository {

    override fun observeMomentumLeaders(window: MomentumWindow, limit: Int): Flow<List<Ticker>> =
        dao.observeMomentumLeaders(window.apiKey, limit).map { rows -> rows.map(TickerEntity::toDomain) }

    override fun observeLastSyncedAt(): Flow<Long?> = dao.observeLastSyncedAt()

    @OptIn(ExperimentalTime::class)
    override suspend fun refresh(): RefreshResult = try {
        val remote = api.fetchScannerCache()
        // Upsert (never wipe-then-insert): a partial failure must not leave the
        // user staring at an empty screen.
        dao.upsertAll(remote.map(TickerDto::toEntity))
        dao.setSyncMetadata(SyncMetadataEntity(lastSyncedAt = Clock.System.now().toEpochMilliseconds()))
        RefreshResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Offline / server error: the cached rows stay on screen untouched.
        RefreshResult.Failed(e.message ?: "Unknown error")
    }
}

private fun TickerDto.toEntity() = TickerEntity(
    ticker = ticker,
    name = name,
    country = country,
    sector = sector,
    priceEur = priceEur,
    updatedAt = updatedAt,
    clenow = clenow,
    mom1m = mom1m,
    mom2m = mom2m,
    mom3m = mom3m,
    mom12m = mom12m,
    forwardPe = forwardPe,
    peg = peg,
    roic = roic,
    r2 = r2,
    // Flatten Python's verdict. Absent `quality_gate` / absent `passes_filters`
    // both collapse to NULL, which the DAO's `qualityPasses = 1` excludes (fail-safe).
    qualityPasses = qualityGate?.passesFilters,
    wyckoffMarkdown = wyckoffMarkdown,
    duplicateOf = duplicateOf,
)

private fun TickerEntity.toDomain() = Ticker(
    ticker = ticker,
    name = name,
    country = country,
    sector = sector,
    priceEur = priceEur,
    clenow = clenow,
    mom1m = mom1m,
    mom2m = mom2m,
    mom3m = mom3m,
    mom12m = mom12m,
    forwardPe = forwardPe,
    peg = peg,
    roic = roic,
    r2 = r2,
)
