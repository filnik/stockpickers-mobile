package app.stockpickers.kmp.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.presentation.MomentumLeadersUiState
import app.stockpickers.kmp.presentation.TickerDetailUiState
import app.stockpickers.kmp.ui.MomentumLeadersScreen
import app.stockpickers.kmp.ui.TickerDetailScreen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshots of the shared Compose Multiplatform screens, run on the host
 * JVM via Robolectric. Lives in :composeApp so it can stage the CMP composeResources
 * onto the unit-test classpath (see `stageComposeResForTest` in build.gradle.kts).
 *
 * `PreviewContextConfigurationEffect()` switches CMP to the classpath resource
 * reader (the one Android Studio previews use), which then finds the staged
 * package-qualified `.cvr` files — so `stringResource()` resolves here.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Plain Application, NOT StockpickersApp: the real one calls initKoin() in onCreate,
// which Robolectric would re-run per test (KoinApplicationAlreadyStarted on the 2nd).
// The stateless screen overloads take their state directly and need no Koin.
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ScreenSnapshotTest {

    private fun leadersState() = MomentumLeadersUiState(
        sort = LeaderSort.STRENGTH,
        geo = GeoFilter.ALL,
        counts = GeoCounts(total = 42, usa = 20, ita = 5, asia = 17),
        leaders = sampleTickers(),
        isLoading = false,
        isRefreshing = false,
        isOffline = false,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private fun detailState() = TickerDetailUiState(
        detail = TickerDetail(
            ticker = "AAPL",
            name = "Apple Inc.",
            country = "United States",
            sector = "Technology",
            priceEur = null,
            clenow = 1.23,
            mom1m = 0.10,
            mom2m = 0.22,
            mom3m = 0.31,
            mom12m = 0.55,
            forwardPe = 25.0,
            peg = 1.8,
            roic = 0.30,
            r2 = 0.95,
            qualityGate = QualityGate(passesFilters = true, reason = null, failedFilter = null),
            updatedAt = "2026-01-15T10:00:00Z",
        ),
        isLoading = false,
    )

    private fun sampleTickers(): List<Ticker> = listOf(
        Ticker("DAVE", "Dave Inc.", "United States", "Technology", null, 7.42, 0.482, 0.30, 0.28, 1.31, 20.8, 0.09, 0.578, 0.26),
        Ticker("SEZL", "Sezzle Inc.", "United States", "Financial Services", null, 6.10, 0.251, 0.22, 0.19, 0.88, 15.0, 0.7, 0.30, 0.61),
        Ticker("VLO", "Valero Energy", "United States", "Energy", null, 5.30, 0.230, 0.20, 0.18, 0.42, 9.0, 0.9, 0.20, 0.74),
        Ticker("2330.TW", "TSMC", "Taiwan", "Technology", null, 4.90, 0.036, 0.12, 0.22, 1.30, 19.0, 0.8, 0.25, 0.97),
        Ticker("BPE.MI", "BPER Banca", "Italy", "Financial Services", null, 4.10, 0.150, 0.16, 0.14, 0.60, 7.0, 0.5, 0.18, 0.55),
        Ticker("8411.T", "Mizuho", "Japan", "Financial Services", null, 3.80, 0.090, 0.10, 0.12, 0.50, 11.0, 0.6, 0.15, 0.62),
    )

    @Test
    fun momentumLeadersScreen_withData() {
        captureRoboImage(filePath = "build/outputs/roborazzi/MomentumLeadersScreen_data.png") {
            PreviewContextConfigurationEffect()
            MaterialTheme {
                MomentumLeadersScreen(
                    state = leadersState(),
                    onSortSelected = {},
                    onGeoSelected = {},
                    onRefresh = {},
                    onErrorDismissed = {},
                    onTickerClick = {},
                )
            }
        }
    }

    @Test
    fun tickerDetailScreen_withData() {
        captureRoboImage(filePath = "build/outputs/roborazzi/TickerDetailScreen_data.png") {
            PreviewContextConfigurationEffect()
            MaterialTheme {
                TickerDetailScreen(
                    state = detailState(),
                    onBackClick = {},
                )
            }
        }
    }
}
