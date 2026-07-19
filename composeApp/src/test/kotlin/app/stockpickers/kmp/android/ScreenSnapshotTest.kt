package app.stockpickers.kmp.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
import kotlin.math.sin
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
        // Quote and period change still render (they come from last/previousClose);
        // only the plot area falls back to its deterministic placeholder. See
        // pricesFixture for why the points are dropped.
        priceSeries = pricesFixture().copy(points = emptyList()),
        profile = profileFixture(),
        isLoading = false,
    )

    /**
     * A profile with every block filled, so the snapshot covers the whole card.
     *
     * The LENGTHS are taken from real rows — bullets run to ~270 characters and the
     * misleadingly-named `consensus` to ~280 — because a fixture of tidy short strings
     * would hide exactly the wrapping this card has to survive. (Live rows are written
     * in Italian, which is what the card's language badge is for; the fixture is
     * English so the baseline stays readable to anyone reviewing this repo.)
     */
    private fun profileFixture() = TickerProfile(
        ticker = "AAPL",
        timelessDescription = "Apple Inc. (headquartered in Cupertino, California) designs " +
            "and sells smartphones, computers and digital services, around an ecosystem " +
            "that ties hardware, software and recurring subscriptions together.",
        currentDescription = "The latest quarter beat expectations on services, while " +
            "hardware stayed flat.",
        pros = listOf(
            "Services margins have expanded for eight consecutive quarters, shifting the " +
                "revenue mix towards recurring income that earns roughly twice the " +
                "margin of hardware.",
            "An installed base of more than two billion active devices.",
        ),
        cons = listOf(
            "Exposure to the Chinese supply chain, on both production and end demand.",
            "iPhone growth has stalled in mature markets.",
        ),
        nextEarnings = NextEarnings(
            date = "2026-08-05",
            daysAway = 12,
            // Named "consensus" upstream but written as prose, not as a rating.
            consensus = "Q3 FY2026 results on 5 August. Watch for: durability of services " +
                "margins, iPhone guidance after the replacement cycle, and commentary on " +
                "AI capital spending.",
        ),
        timelessFreshness = ContentFreshness.FRESH,
        currentFreshness = ContentFreshness.FRESH,
    )

    /**
     * A ~6-month daily close series (rising, with a wave). Stands in for a live Yahoo
     * fetch — the snapshot never touches the network.
     *
     * The DATA is deterministic; the drawing is not. `PriceChart` feeds Vico from a
     * `LaunchedEffect` that suspends on `runTransaction`, and that race against the
     * capture is lost roughly one run in three — measured: 1 failure per 3 verifies,
     * with the plot area blank in the loser. Whichever state a baseline is recorded in,
     * the other one then fails, so callers drop the points (see detailState) and the
     * chart falls back to its placeholder. A screenshot guard that cries wolf a third
     * of the time is worse than one that covers slightly less.
     *
     * Nothing is really lost: the chart never drew reliably here, so no baseline has
     * ever asserted anything about it. Verify the chart on a device or a simulator.
     */
    private fun pricesFixture(): PriceSeries {
        val baseEpoch = 1_700_000_000L
        val points = (0 until 126).map { i ->
            val close = 50.0 + i * 0.9 + 8.0 * sin(i / 7.0)
            PricePoint(epochSeconds = baseEpoch + i * 86_400L, close = close)
        }
        return PriceSeries(
            ticker = "AAPL",
            currency = "USD",
            last = points.last().close,
            previousClose = points[points.lastIndex - 1].close,
            points = points,
        )
    }

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

    /**
     * The MAJORITY case, and the reason it gets its own baseline: upstream covers only
     * part of the universe, so most tickers open with no profile at all. The card must
     * vanish cleanly rather than leave an empty frame or a row of em-dashes.
     */
    @Test
    fun tickerDetailScreen_withoutProfile() {
        captureRoboImage(filePath = "build/outputs/roborazzi/TickerDetailScreen_no_profile.png") {
            PreviewContextConfigurationEffect()
            MaterialTheme {
                TickerDetailScreen(
                    state = detailState().copy(profile = null),
                    onBackClick = {},
                )
            }
        }
    }
}
