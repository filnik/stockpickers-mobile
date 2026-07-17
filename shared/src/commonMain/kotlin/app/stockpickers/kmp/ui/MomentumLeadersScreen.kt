package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stockpickers.kmp.domain.GeoCounts
import app.stockpickers.kmp.domain.GeoFilter
import app.stockpickers.kmp.domain.LeaderSort
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.presentation.MomentumLeadersUiState
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stockpickers.kmp.resources.Res
import app.stockpickers.kmp.resources.action_retry
import app.stockpickers.kmp.resources.caption_clenow
import app.stockpickers.kmp.resources.cd_offline
import app.stockpickers.kmp.resources.cd_refresh
import app.stockpickers.kmp.resources.empty_all
import app.stockpickers.kmp.resources.empty_geo
import app.stockpickers.kmp.resources.geo_all
import app.stockpickers.kmp.resources.leaders_title
import app.stockpickers.kmp.resources.snackbar_offline_cached
import app.stockpickers.kmp.resources.sort_strength
import app.stockpickers.kmp.resources.state_error
import app.stockpickers.kmp.resources.sync_last
import app.stockpickers.kmp.resources.sync_never
import app.stockpickers.kmp.resources.sync_syncing
import app.stockpickers.kmp.resources.time_days_ago
import app.stockpickers.kmp.resources.time_hours_ago
import app.stockpickers.kmp.resources.time_just_now
import app.stockpickers.kmp.resources.time_minutes_ago
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MomentumLeadersScreen(
    onTickerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MomentumLeadersViewModel = koinViewModel(),
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
    val offlineCachedMessage = stringResource(Res.string.snackbar_offline_cached)

    // Surface refresh failures without hiding the cache underneath.
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (message != null && state.leaders.isNotEmpty()) {
            snackbarHostState.showSnackbar(offlineCachedMessage)
            onErrorDismissed()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.leaders_title), style = MaterialTheme.typography.titleMedium)
                        SyncStatusLabel(state)
                    }
                },
                actions = {
                    if (state.isOffline) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = stringResource(Res.string.cd_offline),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.cd_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.sort.ordinal) {
                LeaderSort.entries.forEach { sort ->
                    Tab(
                        selected = state.sort == sort,
                        onClick = { onSortSelected(sort) },
                        text = { Text(sort.displayLabel()) },
                    )
                }
            }
            GeoFilterChips(
                selected = state.geo,
                counts = state.counts,
                onGeoSelected = onGeoSelected,
            )
            if (state.isRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading -> CenteredBox { CircularProgressIndicator() }
                    state.isFatal -> CenteredBox {
                        ErrorContent(state.errorMessage.orEmpty(), onRefresh)
                    }
                    state.isEmpty -> CenteredBox {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.leaders, key = { _, item -> item.ticker }) { index, ticker ->
                            LeaderCard(
                                rank = index + 1,
                                ticker = ticker,
                                sort = state.sort,
                                onClick = { onTickerClick(ticker.ticker) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusLabel(state: MomentumLeadersUiState) {
    // Recompute the relative label roughly once a minute.
    var now by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(state.lastSyncedAt) {
        while (true) {
            now = currentTimeMillis()
            delay(30_000)
        }
    }
    val text = when {
        state.isRefreshing -> stringResource(Res.string.sync_syncing)
        state.lastSyncedAt != null ->
            stringResource(Res.string.sync_last, relativeTimeLabel(state.lastSyncedAt, now))
        else -> stringResource(Res.string.sync_never)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (state.isOffline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Country chips. The count is the qualifying POOL per bucket, so "🇯🇵 ASIA 22"
 * next to a 10-card list means "the best 10 of 22" — see `GeoCounts`.
 */
@Composable
private fun GeoFilterChips(
    selected: GeoFilter,
    counts: GeoCounts,
    onGeoSelected: (GeoFilter) -> Unit,
) {
    // All four chips fit an iPhone-width screen at the default text size, but only
    // just — the scroll is the escape hatch for a narrower device or large dynamic
    // type, where a clipped count would hide the very number the chip is for.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = chipRowPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(chipSpacing),
    ) {
        GeoFilter.entries.forEach { geo ->
            FilterChip(
                selected = selected == geo,
                onClick = { onGeoSelected(geo) },
                label = {
                    Text(
                        text = "${geo.displayLabel()} ${counts[geo]}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

private val chipRowPadding = 8.dp
private val chipSpacing = 6.dp

@Composable
private fun LeaderCard(rank: Int, ticker: Ticker, sort: LeaderSort, onClick: () -> Unit) {
    // The headline metric is always the one the board is ranked by, so the
    // ordering on screen is self-evident. "Forza" ranks by clenow, which the gate
    // guarantees is positive — hence the unconditional green.
    val window = sort.window
    val momentum = window?.let(ticker::momentumFor)
    val headline = if (window == null) formatClenow(ticker.clenow) else formatMomentum(momentum)
    val headlineColor = when {
        window == null -> PositiveGreen
        momentum == null -> MaterialTheme.colorScheme.onSurfaceVariant
        momentum >= 0 -> PositiveGreen
        else -> NegativeRed
    }
    val clenowLabel = stringResource(Res.string.caption_clenow)
    val caption = if (window == null) clenowLabel else "$clenowLabel ${formatClenow(ticker.clenow)}"
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = ticker.ticker,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = ticker.name ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(ticker.country, ticker.sector).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = headlineColor,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(stringResource(Res.string.state_error), style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
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

/**
 * Localised "x ago" fragment for the sync line. Replaces the old
 * `formatRelativeTime`, which baked the English words in — this reads them from
 * resources so the whole label localises.
 */
@Composable
private fun relativeTimeLabel(epochMillis: Long, nowMillis: Long): String {
    val diff = nowMillis - epochMillis
    val minutes = if (diff < 0) 0L else diff / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.time_just_now)
        minutes < 60 -> stringResource(Res.string.time_minutes_ago, minutes.toInt())
        minutes < 24 * 60 -> stringResource(Res.string.time_hours_ago, (minutes / 60).toInt())
        else -> stringResource(Res.string.time_days_ago, (minutes / (24 * 60)).toInt())
    }
}
