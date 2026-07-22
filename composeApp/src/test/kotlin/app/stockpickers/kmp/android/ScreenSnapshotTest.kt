package app.stockpickers.kmp.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.PricePoint
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.RefreshFailure
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
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
import kotlin.math.sin

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
        refreshFailure = null,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private fun detailState() = TickerDetailUiState(
        detail = sampleDetail(),
        // Quote and period change still render (they come from last/previousClose);
        // only the plot area falls back to its deterministic placeholder. See
        // pricesFixture for why the points are dropped.
        priceSeries = pricesFixture().copy(points = emptyList()),
        profile = profileFixture(),
        isLoading = false,
    )

    /**
     * The scanner row behind the detail screen. [qualityGate] and the two momentum
     * windows are parameters because they are what the card BRANCHES on — a verdict
     * has three renderings and a momentum reading three colours, and each needs its
     * own baseline.
     */
    private fun sampleDetail(
        qualityGate: QualityGate? = QualityGate(passesFilters = true, reason = null, failedFilter = null),
        mom1m: Double? = 0.10,
        mom2m: Double? = 0.22,
    ) = TickerDetail(
        ticker = "AAPL",
        name = "Apple Inc.",
        country = "United States",
        sector = "Technology",
        priceEur = null,
        clenow = 1.23,
        mom1m = mom1m,
        mom2m = mom2m,
        mom3m = 0.31,
        mom12m = 0.55,
        forwardPe = 25.0,
        peg = 1.8,
        roic = 0.30,
        r2 = 0.95,
        qualityGate = qualityGate,
        updatedAt = "2026-01-15T10:00:00Z",
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

    /**
     * Positional [Ticker] construction with fourteen arguments is unreadable and,
     * worse, silently wrong if two of the eight Doubles are swapped — in fixture
     * data that means a snapshot that "passes" against the wrong numbers. This
     * names the ones that carry meaning and defaults the rest.
     *
     * These fixtures deliberately live here rather than in :shared's commonTest
     * ModelCreators: that source set is not visible from another module without
     * test fixtures, and the values below are chosen for how they RENDER (name
     * lengths, suffixed symbols) rather than for reuse.
     */
    private fun sampleTicker(
        symbol: String,
        name: String,
        country: String,
        sector: String,
        clenow: Double,
        mom1m: Double,
        mom2m: Double = 0.20,
        mom3m: Double = 0.18,
        mom12m: Double = 0.60,
        forwardPe: Double = 12.0,
        peg: Double = 0.7,
        roic: Double = 0.22,
        r2: Double = 0.70,
    ): Ticker = Ticker(
        ticker = symbol,
        name = name,
        country = country,
        sector = sector,
        priceEur = null,
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

    private fun sampleTickers(): List<Ticker> = listOf(
        sampleTicker("DAVE", "Dave Inc.", "United States", "Technology", clenow = 7.42, mom1m = 0.482),
        sampleTicker("SEZL", "Sezzle Inc.", "United States", "Financial Services", clenow = 6.10, mom1m = 0.251),
        sampleTicker("VLO", "Valero Energy", "United States", "Energy", clenow = 5.30, mom1m = 0.230),
        // Suffixed symbols on purpose: the board must render 2330.TW / BPE.MI / 8411.T
        // without the ticker column pushing the numbers out of alignment.
        sampleTicker("2330.TW", "TSMC", "Taiwan", "Technology", clenow = 4.90, mom1m = 0.036),
        sampleTicker("BPE.MI", "BPER Banca", "Italy", "Financial Services", clenow = 4.10, mom1m = 0.150),
        sampleTicker("8411.T", "Mizuho", "Japan", "Financial Services", clenow = 3.80, mom1m = 0.090),
    )

    /**
     * Same board with LOSERS on it. [sampleTickers] is all-positive, so the red and
     * grey branches of the momentum columns never reached a baseline without this.
     */
    private fun mixedSignTickers(): List<Ticker> {
        val base = sampleTickers()
        return listOf(base[0], base[1].copy(mom1m = -0.084), base[2].copy(mom1m = null)) + base.drop(3)
    }

    // ---- capture helpers: one per screen, so each state below is a single line ----
    //
    // Goldens live under src/test/snapshots/images and are COMMITTED. They used to be
    // written into build/, which is git-ignored and wiped by `clean`: on a fresh
    // checkout `verifyRoborazziDebug` then had no baseline to compare against, and
    // every test here silently degraded to "the screen composes without throwing".
    //
    // Caveat worth knowing rather than hiding: goldens are host-dependent (JVM
    // version, OS font rendering). On a solo project with no CI that is fine; a CI
    // runner on a different image would need its own baselines.

    private fun captureLeaders(name: String, state: MomentumLeadersUiState, freezeAnimations: Boolean = false) {
        captureRoboImage(filePath = "$SNAPSHOT_DIR/MomentumLeadersScreen_$name.png") {
            PreviewContextConfigurationEffect()
            Frozen(freezeAnimations) {
                MaterialTheme {
                    MomentumLeadersScreen(
                        state = state,
                        onSortSelected = {},
                        onGeoSelected = {},
                        onRefresh = {},
                        onErrorDismissed = {},
                        onTickerClick = {},
                    )
                }
            }
        }
    }

    private fun captureDetail(name: String, state: TickerDetailUiState, freezeAnimations: Boolean = false) {
        captureRoboImage(filePath = "$SNAPSHOT_DIR/TickerDetailScreen_$name.png") {
            PreviewContextConfigurationEffect()
            Frozen(freezeAnimations) {
                MaterialTheme {
                    TickerDetailScreen(state = state, onBackClick = {})
                }
            }
        }
    }

    /**
     * Pins indeterminate animations to their first frame, for the states whose whole
     * subject IS a spinner.
     *
     * `LocalInspectionMode` is what Studio previews set, and Compose's
     * `InfiniteTransition` — which drives both progress indicators — holds at its start
     * value under it instead of running. Without this the captured pixels depend on how
     * long the JVM has been alive when the test runs, so the four spinner baselines
     * shifted the moment `forkEvery` changed the fork boundaries: a red build with no
     * UI change behind it.
     *
     * A comparison threshold was the obvious alternative and is the WRONG tool here. On
     * this screen a rotated arc differs by ~0.26% of the pixels while a spinner that
     * vanished entirely differs by only ~0.13% — any threshold loose enough to permit
     * the first also permits the second, which is the regression the test exists to
     * catch. Freeze the animation; do not widen the tolerance.
     *
     * Applied ONLY where an indeterminate animation is on screen: inspection mode
     * changes how some composables render, and the other fifteen baselines are stable
     * without it.
     */
    @Composable
    private fun Frozen(enabled: Boolean, content: @Composable () -> Unit) {
        // ONE call site for `content`, not one per branch. Branching around it would
        // give the slot two different positions in the composition, so flipping
        // `enabled` would discard its internal state — harmless in a snapshot, which
        // renders once, but it is what `compose:content-slot-reused` warns about and
        // the branch buys nothing. Re-providing the current value is a no-op.
        val inspection = if (enabled) true else LocalInspectionMode.current
        CompositionLocalProvider(LocalInspectionMode provides inspection) { content() }
    }

    private companion object {
        /** Relative to the :composeApp module directory. Tracked in git. */
        const val SNAPSHOT_DIR = "src/test/snapshots/images"
    }

    // ---- MomentumLeadersScreen: one baseline per branch of its render `when`, plus
    // the header states that sit orthogonal to it (badge, refresh bar). ----

    @Test
    fun momentumLeadersScreen_withData() = captureLeaders("data", leadersState())

    /** The very first frame: no cache to show yet. Also the UiState's own defaults. */
    @Test
    fun momentumLeadersScreen_loading() = captureLeaders("loading", MomentumLeadersUiState(), freezeAnimations = true)

    @Test
    fun momentumLeadersScreen_empty() = captureLeaders("empty", leadersState().copy(leaders = emptyList()))

    /** The empty text names the CHIP as well as the tab — a different string. */
    @Test
    fun momentumLeadersScreen_emptyForGeoFilter() =
        captureLeaders("empty_geo", leadersState().copy(leaders = emptyList(), geo = GeoFilter.IT))

    /** Never synced AND nothing cached: the one case an error may take the screen. */
    @Test
    fun momentumLeadersScreen_fatalError() = captureLeaders(
        "fatal",
        leadersState().copy(leaders = emptyList(), errorMessage = "Unable to reach the server"),
    )

    /**
     * Stale cache, badge up. `errorMessage` stays null on purpose: that is the state
     * AFTER the snackbar was dismissed, which is precisely what the sticky badge is
     * for — and it keeps a snackbar animation out of the capture.
     */
    @Test
    fun momentumLeadersScreen_offline() =
        captureLeaders("offline", leadersState().copy(refreshFailure = RefreshFailure.OFFLINE))

    /** The regression this pair guards: a server fault must NOT wear the cloud icon. */
    @Test
    fun momentumLeadersScreen_serverFailure() =
        captureLeaders("server_failure", leadersState().copy(refreshFailure = RefreshFailure.SERVER))

    @Test
    fun momentumLeadersScreen_refreshing() =
        captureLeaders("refreshing", leadersState().copy(isRefreshing = true), freezeAnimations = true)

    /**
     * A momentum tab, which swaps the two numeric columns (momentum becomes primary,
     * clenow secondary) and relabels the headers. Paired with a selected chip and a
     * mixed-sign list so the whole variable half of the table renders at once.
     */
    @Test
    fun momentumLeadersScreen_momentumSort() = captureLeaders(
        "momentum_sort",
        leadersState().copy(sort = LeaderSort.ONE_MONTH, geo = GeoFilter.US, leaders = mixedSignTickers()),
    )

    // ---- TickerDetailScreen ----

    @Test
    fun tickerDetailScreen_withData() = captureDetail("data", detailState())

    /**
     * The MAJORITY case, and the reason it gets its own baseline: upstream covers only
     * part of the universe, so most tickers open with no profile at all. The card must
     * vanish cleanly rather than leave an empty frame or a row of em-dashes.
     */
    @Test
    fun tickerDetailScreen_withoutProfile() = captureDetail("no_profile", detailState().copy(profile = null))

    @Test
    fun tickerDetailScreen_loading() = captureDetail("loading", TickerDetailUiState(), freezeAnimations = true)

    /** Room answered and the row is gone — a spinner here would never end. */
    @Test
    fun tickerDetailScreen_notCached() = captureDetail("not_cached", TickerDetailUiState(isLoading = false))

    /** A freshly-picked range, still fetching: a spinner, NOT the "no data" text. */
    @Test
    fun tickerDetailScreen_chartLoading() =
        captureDetail("chart_loading", detailState().copy(isChartLoading = true), freezeAnimations = true)

    /**
     * A TALLER screen for the cards that sit at the bottom of the scroll.
     *
     * The detail screen is one long column and the profile card is ~600dp of prose, so
     * on the standard 800dp device the momentum and quality cards never reach the
     * capture. Recorded at 800dp these three baselines came out BYTE-IDENTICAL to
     * `data` — three tests asserting nothing while looking green. The screenshot is the
     * viewport, not the composition: anything below the fold has to be given room.
     */
    @Test
    @Config(qualifiers = "w360dp-h1600dp-xhdpi")
    fun tickerDetailScreen_qualityRejected() = captureDetail(
        "quality_rejected",
        detailState().copy(
            detail = sampleDetail(
                qualityGate = QualityGate(
                    passesFilters = false,
                    reason = "ROIC has sat below the floor for three consecutive years.",
                    failedFilter = "roic",
                ),
            ),
        ),
    )

    /** A null verdict is UNKNOWN and must never be painted as a pass. */
    @Test
    @Config(qualifiers = "w360dp-h1600dp-xhdpi")
    fun tickerDetailScreen_qualityNotEvaluated() =
        captureDetail("quality_unknown", detailState().copy(detail = sampleDetail(qualityGate = null)))

    /**
     * Red momentum, a grey missing one, and a period change that fell. Tall for the
     * same reason as the quality baselines: without it only the period change at the
     * top would differ, and the momentum tiles this test is named after would sit
     * below the fold, unverified.
     */
    @Test
    @Config(qualifiers = "w360dp-h1600dp-xhdpi")
    fun tickerDetailScreen_negativeMomentum() = captureDetail(
        "negative_momentum",
        detailState().copy(
            detail = sampleDetail(mom1m = -0.12, mom2m = null),
            priceSeries = pricesFixture().copy(points = emptyList(), last = 92.0, previousClose = 100.0),
        ),
    )

    @Test
    fun tickerDetailScreen_staleProfile() = captureDetail(
        "profile_stale",
        detailState().copy(profile = profileFixture().copy(currentFreshness = ContentFreshness.STALE)),
    )

    /** Undatable text earns NO badge — the card must not fall back to "up to date". */
    @Test
    fun tickerDetailScreen_undatedProfile() = captureDetail(
        "profile_undated",
        detailState().copy(
            profile = profileFixture().copy(
                timelessFreshness = ContentFreshness.UNKNOWN,
                currentFreshness = ContentFreshness.UNKNOWN,
            ),
        ),
    )
}
