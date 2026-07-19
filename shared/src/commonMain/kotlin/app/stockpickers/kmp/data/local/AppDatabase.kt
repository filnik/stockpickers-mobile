package app.stockpickers.kmp.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

// v2 added `tickers.qualityReason` / `tickers.qualityFailedFilter`.
// v3 added the `price_series` table (cached Yahoo price history for the chart).
// v4 made `price_series` keyed by (ticker, rangeKey) — one cached row per chart
//    range (1D/1W/1M/3M/6M/1Y) for the detail chart's range selector.
// v5 added the `ticker_profiles` table (the written profile from Supabase's
//    `descriptions_cache`: description, pros/cons, next earnings).
// No Migration is written on purpose: every row here is a re-downloadable cache,
// and the builder already declares `fallbackToDestructiveMigration(dropAllTables = true)`.
@Database(
    entities = [
        TickerEntity::class,
        PriceSeriesEntity::class,
        TickerProfileEntity::class,
        SyncMetadataEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scannerDao(): ScannerDao

    companion object {
        const val FILE_NAME = "stockpickers.db"
    }
}

/** Room's KSP processor generates the `actual` for every target. */
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
