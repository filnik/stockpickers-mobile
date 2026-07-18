package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.presentation.TickerDetailSideEffect
import app.stockpickers.kmp.presentation.TickerDetailUiState
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import androidx.compose.runtime.LaunchedEffect
import app.stockpickers.kmp.resources.Res
import app.stockpickers.kmp.resources.cd_back
import app.stockpickers.kmp.resources.detail_chart_range_unavailable
import app.stockpickers.kmp.resources.detail_chart_unavailable
import app.stockpickers.kmp.resources.detail_clenow_score
import app.stockpickers.kmp.resources.detail_forward_pe
import app.stockpickers.kmp.resources.detail_fundamentals
import app.stockpickers.kmp.resources.detail_momentum
import app.stockpickers.kmp.resources.detail_not_cached
import app.stockpickers.kmp.resources.detail_price
import app.stockpickers.kmp.resources.detail_peg
import app.stockpickers.kmp.resources.detail_pipeline_updated
import app.stockpickers.kmp.resources.detail_quality_gate
import app.stockpickers.kmp.resources.detail_r2_fit
import app.stockpickers.kmp.resources.detail_roic
import app.stockpickers.kmp.resources.detail_trend_quality
import app.stockpickers.kmp.resources.quality_failed_filter
import app.stockpickers.kmp.resources.quality_not_evaluated
import app.stockpickers.kmp.resources.quality_passes
import app.stockpickers.kmp.resources.quality_rejected
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point. Collects state + drains the one-shot side effect channel.
 */
@Composable
fun TickerDetailScreen(
    viewModel: TickerDetailViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                TickerDetailSideEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    TickerDetailScreen(
        state = state,
        onBackClick = viewModel::onBackClick,
        onRangeSelected = viewModel::selectRange,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickerDetailScreen(
    state: TickerDetailUiState,
    onBackClick: () -> Unit,
    onRangeSelected: (ChartRange) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.ticker ?: "—",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        val detail = state.detail
        when {
            state.isLoading -> CenteredFill { CircularProgressIndicator() }
            detail == null -> CenteredFill {
                Text(
                    stringResource(Res.string.detail_not_cached),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(detail)
                PriceChartCard(
                    series = state.priceSeries,
                    selectedRange = state.selectedRange,
                    isChartLoading = state.isChartLoading,
                    onRangeSelected = onRangeSelected,
                )
                MomentumCard(detail)
                TrendCard(detail)
                FundamentalsCard(detail)
                QualityGateCard(detail.qualityGate)
                detail.updatedAt?.let {
                    Text(
                        text = stringResource(Res.string.detail_pipeline_updated, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(detail: TickerDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = detail.name ?: detail.ticker,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = listOfNotNull(detail.country, detail.sector).joinToString(" · ").ifEmpty { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatPriceEur(detail.priceEur),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Price chart card, TradingView-style. Shows the latest quote (value + currency),
 * the SELECTED range's change (absolute + %, coloured), a segmented range selector
 * (1D…1Y), then the close-line/area chart for that range.
 *
 * Empty-series handling has two faces so a chip switch never flickers to an error:
 * while [isChartLoading] a soft spinner stands in for a not-yet-cached range; only
 * once loading has settled with no points do we show "no data for this range".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceChartCard(
    series: PriceSeries?,
    selectedRange: ChartRange,
    isChartLoading: Boolean,
    onRangeSelected: (ChartRange) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.detail_price)) {
        series?.last?.let { last ->
            Text(
                text = formatQuote(last, series.currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }

        // The selected PERIOD's change — moves with the range (Yahoo's chartPreviousClose
        // is the close before the requested window). Hidden when either bound is missing.
        series?.periodChange?.let { change ->
            val color = if (change >= 0) PositiveGreen else NegativeRed
            Text(
                text = formatSignedQuote(change, series.currency) +
                    "  (" + formatSignedPercent(series.periodChangePercent) + ")",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }

        ChartRangeSelector(selected = selectedRange, onRangeSelected = onRangeSelected)

        val points = series?.points.orEmpty()
        when {
            points.isNotEmpty() -> PriceChart(
                points = points,
                lineColor = trendColor(series),
            )
            // Soft loading: a freshly-picked, not-yet-cached range is fetching — keep the
            // chart's footprint and show a spinner rather than the empty-state text.
            isChartLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(CHART_PLACEHOLDER_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            else -> Text(
                text = stringResource(Res.string.detail_chart_range_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CHART_PLACEHOLDER_HEIGHT = 180.dp

/**
 * The TradingView-style range chips (1D · 1W · 1M · 3M · 6M · 1Y) as a Material3
 * single-choice segmented row. Labels are domain symbols from [ChartRange] (never
 * translated, like the momentum-window labels).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartRangeSelector(
    selected: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val ranges = ChartRange.entries
        ranges.forEachIndexed { index, range ->
            SegmentedButton(
                selected = selected == range,
                onClick = { onRangeSelected(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
                label = {
                    Text(
                        text = range.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/** Green when the last close holds at/above the previous, red when it slipped. */
private fun trendColor(series: PriceSeries?): Color {
    val last = series?.last
    val previous = series?.previousClose
    return if (last != null && previous != null && last < previous) NegativeRed else PositiveGreen
}

@Composable
private fun MomentumCard(detail: TickerDetail) {
    SectionCard(title = stringResource(Res.string.detail_momentum)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MomentumCell("1M", detail.mom1m)
            MomentumCell("2M", detail.mom2m)
            MomentumCell("3M", detail.mom3m)
            MomentumCell("12M", detail.mom12m)
        }
    }
}

@Composable
private fun MomentumCell(label: String, fraction: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatMomentum(fraction),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                fraction == null -> MaterialTheme.colorScheme.onSurfaceVariant
                fraction >= 0 -> PositiveGreen
                else -> NegativeRed
            },
        )
    }
}

@Composable
private fun TrendCard(detail: TickerDetail) {
    SectionCard(title = stringResource(Res.string.detail_trend_quality)) {
        MetricRow(stringResource(Res.string.detail_clenow_score), formatClenow(detail.clenow))
        HorizontalDivider()
        // R² is the regularity of the climb (0..1), not a percentage.
        MetricRow(stringResource(Res.string.detail_r2_fit), formatRatio(detail.r2))
    }
}

@Composable
private fun FundamentalsCard(detail: TickerDetail) {
    SectionCard(title = stringResource(Res.string.detail_fundamentals)) {
        MetricRow(stringResource(Res.string.detail_forward_pe), formatRatio(detail.forwardPe, decimals = 1))
        HorizontalDivider()
        MetricRow(stringResource(Res.string.detail_peg), formatRatio(detail.peg))
        HorizontalDivider()
        MetricRow(stringResource(Res.string.detail_roic), formatPercent(detail.roic))
    }
}

/**
 * The pipeline's verdict, rendered verbatim.
 *
 * Three distinct states — passed / rejected / not evaluated. A null
 * `passesFilters` is UNKNOWN and must never be painted as a pass.
 */
@Composable
private fun QualityGateCard(gate: QualityGate?) {
    val passes = gate?.passesFilters
    val (icon, tint, headline) = when (passes) {
        true -> Triple(Icons.Default.CheckCircle, PositiveGreen, stringResource(Res.string.quality_passes))
        false -> Triple(Icons.Default.Cancel, NegativeRed, stringResource(Res.string.quality_rejected))
        null -> Triple(
            Icons.Default.HelpOutline,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(Res.string.quality_not_evaluated),
        )
    }

    SectionCard(title = stringResource(Res.string.detail_quality_gate)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        gate?.failedFilter?.takeIf { it.isNotBlank() }?.let {
            MetricRow(stringResource(Res.string.quality_failed_filter), it)
        }
        gate?.reason?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun CenteredFill(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
