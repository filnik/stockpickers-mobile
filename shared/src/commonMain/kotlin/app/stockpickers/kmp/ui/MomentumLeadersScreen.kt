package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stockpickers.kmp.domain.MomentumWindow
import app.stockpickers.kmp.domain.Ticker
import app.stockpickers.kmp.presentation.MomentumLeadersUiState
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

private val PositiveGreen = Color(0xFF1B873B)
private val NegativeRed = Color(0xFFD32F2F)

@Composable
fun MomentumLeadersScreen(
    modifier: Modifier = Modifier,
    viewModel: MomentumLeadersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MomentumLeadersScreen(
        state = state,
        onWindowSelected = viewModel::selectWindow,
        onRefresh = viewModel::refresh,
        onErrorDismissed = viewModel::dismissError,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentumLeadersScreen(
    state: MomentumLeadersUiState,
    onWindowSelected: (MomentumWindow) -> Unit,
    onRefresh: () -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface refresh failures without hiding the cache underneath.
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (message != null && state.leaders.isNotEmpty()) {
            snackbarHostState.showSnackbar("Offline — showing cached data")
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
                        Text("Momentum Leaders", style = MaterialTheme.typography.titleMedium)
                        SyncStatusLabel(state)
                    }
                },
                actions = {
                    if (state.isOffline) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Offline",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.window.ordinal) {
                MomentumWindow.entries.forEach { window ->
                    Tab(
                        selected = state.window == window,
                        onClick = { onWindowSelected(window) },
                        text = { Text(window.label) },
                    )
                }
            }
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
                            "No leaders for ${state.window.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.leaders, key = { it.ticker }) { ticker ->
                            LeaderCard(
                                rank = state.leaders.indexOf(ticker) + 1,
                                ticker = ticker,
                                window = state.window,
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
        state.isRefreshing -> "Syncing…"
        state.lastSyncedAt != null -> "Last synced ${formatRelativeTime(state.lastSyncedAt, now)}"
        else -> "Never synced"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (state.isOffline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LeaderCard(rank: Int, ticker: Ticker, window: MomentumWindow) {
    val momentum = ticker.momentumFor(window)
    Card(
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
                    text = formatMomentum(momentum),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        momentum == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        momentum >= 0 -> PositiveGreen
                        else -> NegativeRed
                    },
                )
                Text(
                    text = "clenow ${formatClenow(ticker.clenow)}",
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
        Text("Couldn't load leaders", style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
