package app.stockpickers.kmp.ui

import androidx.compose.material3.MaterialTheme
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.modelcreators.TickerModelCreator
import app.stockpickers.kmp.presentation.MomentumLeadersUiState
import app.stockpickers.kmp.presentation.TickerDetailUiState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshots of the two screens, rendered by the REAL Compose UI on the
 * host JVM through Robolectric's native graphics. Uses the STATELESS overloads
 * with fake UiState (no Koin, no ViewModel) — exactly what those overloads exist
 * for. Record with `recordRoborazzi*`, then compare with `verifyRoborazzi*`.
 *
 * `lastSyncedAt` is null so the sync label reads a fixed "never synced" string
 * rather than a clock-relative one — snapshots must be deterministic.
 */
/*
 * DISABLED (bleeding-edge tooling gap, not a code defect): Compose Multiplatform
 * string resources fail to load under the AGP 9 KMP `androidHostTest` + Robolectric
 * classpath. The compiled `.cvr` index (composeResources/.../strings.commonMain.cvr)
 * is generated for the iOS and Android *app* targets but is NOT placed on the
 * host-test runtime classpath, so `stringResource()` throws
 * `MissingResourceException` during capture. `PreviewContextConfigurationEffect()`
 * wires the Android Context but cannot conjure a resource file that isn't there.
 *
 * The screens themselves are verified visually by real on-device screenshots
 * (iOS simulator + Android emulator). Re-enable once CMP wires composeResources
 * into the KMP android host-test classpath.
 */
@Ignore("CMP composeResources not on the AGP9 KMP androidHostTest classpath — see comment above")
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ScreenSnapshotTest {

    private fun leadersState() = MomentumLeadersUiState(
        sort = LeaderSort.STRENGTH,
        geo = GeoFilter.ALL,
        counts = GeoCounts(total = 42, usa = 20, ita = 5, asia = 17),
        leaders = TickerModelCreator.list(6),
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

    @Test
    fun momentumLeadersScreen_withData() {
        captureRoboImage(filePath = "MomentumLeadersScreen_data.png") {
            // CMP's Android resource reader needs a Context; Robolectric has one but
            // does not wire it into the resource system. This effect supplies it,
            // exactly as @Preview does — without it stringResource() throws.
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
        captureRoboImage(filePath = "TickerDetailScreen_data.png") {
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
