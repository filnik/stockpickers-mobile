package app.stockpickers.kmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.RefreshFailure
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.presentation.MomentumLeadersUiState
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import app.stockpickers.kmp.resources.Res
import app.stockpickers.kmp.resources.action_retry
import app.stockpickers.kmp.resources.caption_clenow
import app.stockpickers.kmp.resources.cd_offline
import app.stockpickers.kmp.resources.cd_refresh
import app.stockpickers.kmp.resources.cd_sync_failed
import app.stockpickers.kmp.resources.col_title
import app.stockpickers.kmp.resources.empty_all
import app.stockpickers.kmp.resources.empty_geo
import app.stockpickers.kmp.resources.geo_all
import app.stockpickers.kmp.resources.leaders_title
import app.stockpickers.kmp.resources.snackbar_offline_cached
import app.stockpickers.kmp.resources.snackbar_refresh_failed_cached
import app.stockpickers.kmp.resources.snackbar_server_cached
import app.stockpickers.kmp.resources.sort_strength
import app.stockpickers.kmp.resources.state_error
import org.jetbrains.compose.resources.stringResource

@Composable
fun MomentumLeadersScreen(
    viewModel: MomentumLeadersViewModel,
    onTickerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MomentumLeadersScreen(
        state = state,
        onSortSelected = viewModel::selectSort,
        onGeoSelected = viewModel::selectGeo,
        onRefresh = viewModel::refresh,
        onErrorDismissed = viewModel::dismissError,
        onTickerClick = onTickerClick,
        modifier = modifier,
    )
}

/**
 * The leaders board: a header and two rows of segmented controls over a dense
 * ranked TABLE.
 *
 * The table (rather than a card per ticker) is the deliberate choice: the job on
 * this screen is comparing a hundred names, and monospaced figures in fixed
 * columns can be compared by shape without being read. Cards would show two names
 * per screen; this shows ten. The per-ticker detail is where density gives way to
 * explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentumLeadersScreen(
    state: MomentumLeadersUiState,
    onSortSelected: (LeaderSort) -> Unit,
    onGeoSelected: (GeoFilter) -> Unit,
    onRefresh: () -> Unit,
    onErrorDismissed: () -> Unit,
    onTickerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // stringResource is @Composable, so the message must be resolved here and
    // captured — it cannot be read inside the LaunchedEffect coroutine below.
    val failureMessage = when (state.refreshFailure) {
        RefreshFailure.OFFLINE -> stringResource(Res.string.snackbar_offline_cached)
        RefreshFailure.SERVER -> stringResource(Res.string.snackbar_server_cached)
        RefreshFailure.UNKNOWN -> stringResource(Res.string.snackbar_refresh_failed_cached)
        null -> null
    }

    // Keyed on the message, so the callback must not be captured directly: a caller
    // passing a fresh lambda would leave this effect invoking a stale one.
    val currentOnErrorDismissed by rememberUpdatedState(onErrorDismissed)

    // Surface refresh failures without hiding the cache underneath.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null && failureMessage != null && state.leaders.isNotEmpty()) {
            snackbarHostState.showSnackbar(failureMessage)
            currentOnErrorDismissed()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoardHeader(state = state, onRefresh = onRefresh)
            SortSegments(selected = state.sort, onSortSelected = onSortSelected)
            GeoFilterChips(selected = state.geo, counts = state.counts, onGeoSelected = onGeoSelected)
            if (state.isRefreshing) {
                BusyLinearIndicator(Modifier.fillMaxWidth().height(2.dp))
            }

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                state = pullState,
                // Same rule as BusyCircularIndicator: this indicator animates too, and
                // under LocalInspectionMode that animation would spin the frame clock
                // instead of rendering. Dropped rather than frozen — a pull indicator
                // at rest draws nothing anyway, and the refreshing state is already on
                // screen as the bar above.
                indicator = {
                    if (!LocalInspectionMode.current) {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = state.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                },
            ) {
                when {
                    state.isLoading -> CenteredFill { BusyCircularIndicator() }

                    state.isFatal -> CenteredFill {
                        ErrorContent(state.errorMessage.orEmpty(), onRefresh)
                    }

                    state.isEmpty -> CenteredFill {
                        Text(
                            text = when (state.geo) {
                                GeoFilter.ALL -> stringResource(Res.string.empty_all, state.sort.displayLabel())

                                else -> stringResource(
                                    Res.string.empty_geo,
                                    state.geo.displayLabel(),
                                    state.sort.displayLabel(),
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurfaceVariant,
                        )
                    }

                    else -> Column(Modifier.fillMaxSize()) {
                        ColumnHeaders(state.sort)
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(state.leaders, key = { _, item -> item.ticker }) { index, ticker ->
                                LeaderRow(
                                    rank = index + 1,
                                    ticker = ticker,
                                    sort = state.sort,
                                    onClick = { onTickerClick(ticker.ticker) },
                                )
                                HorizontalDivider(thickness = Hairline, color = OutlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Emits the row AND its rule as one unit: two top-level siblings would only lay out
// correctly inside a Column, making the parent's layout an unstated precondition.
@Composable
private fun BoardHeader(state: MomentumLeadersUiState, onRefresh: () -> Unit) = Column {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.leaders_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Primary,
            modifier = Modifier.weight(1f),
        )
        // A cloud with a slash means "your connection"; anything else is upstream's
        // fault and gets the neutral sync badge instead.
        if (state.isStale) {
            Icon(
                imageVector = if (state.isOffline) Icons.Default.CloudOff else Icons.Default.SyncProblem,
                contentDescription = stringResource(
                    if (state.isOffline) Res.string.cd_offline else Res.string.cd_sync_failed,
                ),
                tint = NegativeRed,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.cd_refresh), tint = Primary)
        }
    }
    HorizontalDivider(thickness = Hairline, color = OutlineVariant)
}

/**
 * The ranking tabs, as one fully-rounded track with a filled navy thumb — the same
 * control the web uses for BOTH its tabs and its filters, never an underline.
 */
@Composable
private fun SortSegments(selected: LeaderSort, onSortSelected: (LeaderSort) -> Unit) {
    SegmentedControl(
        items = LeaderSort.entries,
        selected = selected,
        label = { it.displayLabel().uppercase() },
        textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        onSelect = onSortSelected,
        outerPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Country chips. The count is the qualifying POOL per bucket, so "🇯🇵 ASIA 22"
 * next to a 10-row list means "the best 10 of 22" — see `GeoCounts`. Separate
 * pills rather than one track, because the row scrolls: a shared track would clip.
 */
@Composable
private fun GeoFilterChips(selected: GeoFilter, counts: GeoCounts, onGeoSelected: (GeoFilter) -> Unit) = Column {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GeoFilter.entries.forEach { geo ->
            val active = selected == geo
            Row(
                modifier = Modifier
                    .background(if (active) PrimaryContainer else SurfaceTile, CircleShape)
                    .clickable { onGeoSelected(geo) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = geo.displayLabel().uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = if (active) Color.White else OnSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = "${counts[geo]}",
                    style = monoStyle(11, FontWeight.Normal),
                    color = (if (active) Color.White else OnSurfaceVariant).copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Which metric occupies which column, derived from the sort.
 *
 * The ranking key is ALWAYS the rightmost column, emphasised; the other metric
 * sits beside it as context. That way the ordering on screen explains itself —
 * you never have to remember which tab you are on to read the list.
 */
private data class Columns(val secondaryLabel: String, val primaryLabel: String)

@Composable
private fun columnsFor(sort: LeaderSort): Columns {
    val clenow = stringResource(Res.string.caption_clenow).uppercase()
    return if (sort.window == null) {
        Columns(secondaryLabel = LeaderSort.ONE_MONTH.label, primaryLabel = clenow)
    } else {
        Columns(secondaryLabel = clenow, primaryLabel = sort.label)
    }
}

private val RankWidth = 30.dp
private val SecondaryWidth = 62.dp
private val PrimaryWidth = 72.dp
private val AccentWidth = 3.dp
private val RowPadding = 12.dp

@Composable
private fun ColumnHeaders(sort: LeaderSort) = Column {
    val cols = columnsFor(sort)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RowPadding + AccentWidth, end = RowPadding, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", style = microLabelStyle(), color = OnSurfaceVariant, modifier = Modifier.width(RankWidth))
        Text(
            // Uppercased here, not in the resource: the micro-label look is a
            // TYPOGRAPHIC rule of this design, and a translator shouldn't have to
            // remember to shout. Localised strings stay sentence case on disk.
            text = stringResource(Res.string.col_title).uppercase(),
            style = microLabelStyle(),
            color = OnSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = cols.secondaryLabel,
            style = microLabelStyle(),
            color = OnSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SecondaryWidth),
        )
        Text(
            text = cols.primaryLabel,
            style = microLabelStyle(),
            color = OnSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(PrimaryWidth),
        )
    }
    HorizontalDivider(thickness = Hairline, color = OutlineVariant)
}

/**
 * One ranked row. Figures are monospaced and right-aligned so decimal points line
 * up down the whole column — that vertical alignment is what makes the list
 * comparable at a glance, and it is the reason this is a table and not cards.
 *
 * The top three carry a green accent bar on the leading edge: the only ornament
 * on the screen, and it encodes rank rather than decorating it.
 */
@Composable
private fun LeaderRow(rank: Int, ticker: Ticker, sort: LeaderSort, onClick: () -> Unit) {
    val window = sort.window
    val momentum = window?.let(ticker::momentumFor)
    // The ranking key. "Forza" ranks by clenow, which the gate guarantees positive.
    val primaryText = if (window == null) formatClenow(ticker.clenow) else formatMomentum(momentum)
    val primaryColor = when {
        window == null -> PositiveGreen
        momentum == null -> OnSurfaceVariant
        momentum >= 0 -> PositiveGreen
        else -> NegativeRed
    }
    val secondaryMomentum = if (window == null) ticker.momentumFor(LeaderSort.ONE_MONTH.window!!) else null
    val secondaryText =
        if (window == null) formatMomentum(secondaryMomentum) else formatClenow(ticker.clenow)
    val secondaryColor = when {
        window != null -> OnSurface
        secondaryMomentum == null -> OnSurfaceVariant
        secondaryMomentum >= 0 -> PositiveGreen
        else -> NegativeRed
    }

    Row(
        // Intrinsic min height lets the accent bar match whatever the text ends up
        // needing, instead of a guessed fixed height that leaves gaps at the rules.
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent bar for the podium. Transparent (not absent) for the rest, so
        // every row keeps the same text origin and the columns stay aligned.
        Box(
            Modifier
                .width(AccentWidth)
                .fillMaxHeight()
                .background(if (rank <= 3) PositiveGreen else Color.Transparent),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = monoStyle(14, FontWeight.Normal),
                color = OnSurfaceVariant,
                modifier = Modifier.width(RankWidth),
            )
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = ticker.ticker,
                    style = monoStyle(16),
                    color = Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ticker.name ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = secondaryText,
                style = monoStyle(14, FontWeight.Normal),
                color = secondaryColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(SecondaryWidth),
            )
            Text(
                text = primaryText,
                style = monoStyle(16, FontWeight.Bold),
                color = primaryColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(PrimaryWidth),
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = NegativeRed)
        Text(stringResource(Res.string.state_error), style = MaterialTheme.typography.titleMedium)
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

/**
 * Localised tab label. Only [LeaderSort.STRENGTH] is a word ("Strength"/"Forza");
 * 1M/2M/3M are domain symbols, kept verbatim from the enum.
 */
@Composable
internal fun LeaderSort.displayLabel(): String = when (this) {
    LeaderSort.STRENGTH -> stringResource(Res.string.sort_strength)
    else -> label
}

/**
 * Localised chip label. Only [GeoFilter.ALL] is a word ("All"/"Tutti"); the
 * flagged country codes (🇺🇸 USA …) are domain symbols, kept verbatim.
 */
@Composable
internal fun GeoFilter.displayLabel(): String = when (this) {
    GeoFilter.ALL -> stringResource(Res.string.geo_all)
    else -> label
}
