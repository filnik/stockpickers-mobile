package app.stockpickers.kmp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stockpickers.kmp.domain.ChartRange
import app.stockpickers.kmp.domain.ContentFreshness
import app.stockpickers.kmp.domain.NextEarnings
import app.stockpickers.kmp.domain.PriceSeries
import app.stockpickers.kmp.domain.QualityGate
import app.stockpickers.kmp.domain.TickerDetail
import app.stockpickers.kmp.domain.TickerProfile
import app.stockpickers.kmp.presentation.TickerDetailSideEffect
import app.stockpickers.kmp.presentation.TickerDetailUiState
import app.stockpickers.kmp.presentation.TickerDetailViewModel
import app.stockpickers.kmp.resources.Res
import app.stockpickers.kmp.resources.cd_back
import app.stockpickers.kmp.resources.detail_chart_range_unavailable
import app.stockpickers.kmp.resources.detail_clenow_score
import app.stockpickers.kmp.resources.detail_forward_pe
import app.stockpickers.kmp.resources.detail_fundamentals
import app.stockpickers.kmp.resources.detail_momentum
import app.stockpickers.kmp.resources.detail_not_cached
import app.stockpickers.kmp.resources.detail_peg
import app.stockpickers.kmp.resources.detail_pipeline_updated
import app.stockpickers.kmp.resources.detail_price
import app.stockpickers.kmp.resources.detail_profile
import app.stockpickers.kmp.resources.detail_quality_gate
import app.stockpickers.kmp.resources.detail_r2_fit
import app.stockpickers.kmp.resources.detail_roic
import app.stockpickers.kmp.resources.detail_trend_quality
import app.stockpickers.kmp.resources.profile_cons
import app.stockpickers.kmp.resources.profile_earnings
import app.stockpickers.kmp.resources.profile_earnings_in_days
import app.stockpickers.kmp.resources.profile_earnings_today
import app.stockpickers.kmp.resources.profile_earnings_tomorrow
import app.stockpickers.kmp.resources.profile_fresh
import app.stockpickers.kmp.resources.profile_language
import app.stockpickers.kmp.resources.profile_pros
import app.stockpickers.kmp.resources.profile_stale
import app.stockpickers.kmp.resources.quality_failed_filter
import app.stockpickers.kmp.resources.quality_not_evaluated
import app.stockpickers.kmp.resources.quality_passes
import app.stockpickers.kmp.resources.quality_rejected
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point. Collects state + drains the one-shot side effect channel.
 */
@Composable
fun TickerDetailScreen(viewModel: TickerDetailViewModel, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The effect keys on `viewModel` alone, so it must NOT capture onNavigateBack
    // directly: a caller passing a fresh lambda on recomposition would leave this
    // collector invoking the stale one for the rest of the screen's life.
    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)

    LaunchedEffect(viewModel) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                TickerDetailSideEffect.NavigateBack -> currentOnNavigateBack()
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
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.ticker ?: "—",
                        style = monoStyle(18),
                        color = Primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                            tint = Primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
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
                // Absent for most tickers — upstream only writes a profile for the
                // names its research pass has covered.
                state.profile?.let { ProfileCard(it) }
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

/**
 * Company name, then country and sector as outline pills.
 *
 * `price_eur` is deliberately NOT shown: it is null for every row the pipeline
 * publishes today, so a headline price here would be a permanent em-dash. The
 * live quote in the chart card is the real one, in the market's own currency.
 */
@Composable
private fun Header(detail: TickerDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = detail.name ?: detail.ticker,
            style = MaterialTheme.typography.headlineSmall,
            color = Primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOfNotNull(detail.country, detail.sector).forEach { label ->
                Text(
                    text = label.uppercase(),
                    style = microLabelStyle(),
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .border(Hairline, OutlineVariant, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PriceChartCard(
    series: PriceSeries?,
    selectedRange: ChartRange,
    isChartLoading: Boolean,
    onRangeSelected: (ChartRange) -> Unit,
) {
    SectionCard {
        // Label, quote and period change share ONE line instead of stacking three.
        // Above the fold this card competes with the chart for height, and a 10sp
        // label on a line of its own was buying nothing.
        //
        // A FlowRow, not a Row: the widest real case is a Korean quote in KRW
        // ("229000.00 KRW" plus "+58500.00 KRW (+34.19%)"), which does not fit a phone
        // line — in a Row the percentage was simply cut off. Here that one case wraps
        // to the layout it had before and loses nothing, while every other currency
        // gets the line back.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(Res.string.detail_price).uppercase(),
                style = microLabelStyle(),
                color = OnSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            series?.last?.let { last ->
                Text(
                    text = formatQuote(last, series.currency),
                    style = monoStyle(22, FontWeight.Bold),
                    color = Primary,
                    maxLines = 1,
                )
            }

            // The selected PERIOD's change — moves with the range (Yahoo's
            // chartPreviousClose is the close before the requested window). Hidden when
            // either bound is missing.
            series?.periodChange?.let { change ->
                Text(
                    text = formatSignedQuote(change, series.currency) +
                        "  (" + formatSignedPercent(series.periodChangePercent) + ")",
                    style = monoStyle(12),
                    color = if (change >= 0) PositiveGreen else NegativeRed,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        ChartRangeSelector(selected = selectedRange, onRangeSelected = onRangeSelected)

        val points = series?.points.orEmpty()
        when {
            points.isNotEmpty() -> PlatformPriceChart(
                points = points,
                positive = isTrendPositive(series),
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
@Composable
private fun ChartRangeSelector(selected: ChartRange, onRangeSelected: (ChartRange) -> Unit) {
    SegmentedControl(
        items = ChartRange.entries,
        selected = selected,
        label = { it.label },
        textStyle = microLabelStyle(),
        onSelect = onRangeSelected,
        // Tighter than the board's tabs: this one sits inside a card.
        trackPadding = 3.dp,
        itemSpacing = 3.dp,
        itemVerticalPadding = 7.dp,
    )
}

/**
 * Whether the last close holds at/above the previous one.
 *
 * A fact, not a colour: the chart is drawn by a different toolkit on each platform,
 * so each applies its own palette to this. Unknown counts as positive — the same
 * benefit of the doubt the flat case gets.
 */
private fun isTrendPositive(series: PriceSeries?): Boolean {
    val last = series?.last
    val previous = series?.previousClose
    return !(last != null && previous != null && last < previous)
}

/**
 * The written profile: what the company is, the case for and against it, and when it
 * next reports.
 *
 * Two pills sit in the header, and they say different kinds of thing. Freshness is a
 * property of the DATA (how old the text is against its own TTL) and is hidden when
 * unknowable — an absent badge is honest, a guessed one is not. The language pill is a
 * property of the PRODUCT: this text is always Italian while the UI may not be, and
 * naming that is better than leaving an English-locale reader to wonder.
 *
 * Every section is dropped when empty rather than shown with a placeholder: upstream
 * fills these blocks independently, so partial rows are ordinary, not broken.
 */
@Composable
private fun ProfileCard(profile: TickerProfile) {
    SectionCard(title = stringResource(Res.string.detail_profile)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (profile.freshness) {
                ContentFreshness.FRESH ->
                    Pill(stringResource(Res.string.profile_fresh), PositiveTint, PositiveOnTint)

                ContentFreshness.STALE ->
                    Pill(stringResource(Res.string.profile_stale), WarnTint, WarnAmber)

                // No badge at all: we cannot date this text, so we claim nothing.
                ContentFreshness.UNKNOWN -> Unit
            }
            Pill(stringResource(Res.string.profile_language), InfoTint, OnSurfaceVariant)
        }

        profile.timelessDescription?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
        }
        profile.currentDescription?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }

        if (profile.pros.isNotEmpty() || profile.cons.isNotEmpty()) {
            HorizontalDivider()
            ArgumentList(
                title = stringResource(Res.string.profile_pros),
                items = profile.pros,
                accent = PositiveOnTint,
            )
            ArgumentList(
                title = stringResource(Res.string.profile_cons),
                items = profile.cons,
                accent = NegativeRed,
            )
        }

        profile.nextEarnings?.let { earnings ->
            HorizontalDivider()
            // Three stacked pieces, not one line. `consensus` reads like a rating but
            // is a paragraph of prose upstream — inlining it after the date would run a
            // 300-character sentence through a monospaced one-liner.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(Res.string.profile_earnings).uppercase(),
                    style = microLabelStyle(),
                    color = OnSurfaceVariant,
                )
                earningsWhen(earnings)?.let {
                    Text(text = it, style = monoStyle(13), color = OnSurface)
                }
                earnings.consensus?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One side of the argument, as a titled list of bullets.
 *
 * The accent colour lives on the bullet, not the text: a paragraph of coloured prose
 * is hard to read, while a coloured marker carries the same for/against signal at a
 * glance. Renders nothing when there is nothing to say.
 */
@Composable
private fun ArgumentList(title: String, items: List<String>, accent: Color) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title.uppercase(), style = microLabelStyle(), color = accent)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(5.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface,
                )
            }
        }
    }
}

/**
 * The factual half of the earnings block: "2026-08-05  ·  in 12 days", or null when
 * upstream gave neither.
 *
 * The countdown is only ever present when it could be computed from the date — the
 * domain refuses to replay upstream's frozen one — so this never has to decide whether
 * to trust the number.
 */
@Composable
private fun earningsWhen(earnings: NextEarnings): String? = listOfNotNull(
    earnings.date,
    earnings.daysAway?.let { days ->
        when {
            days < 0 -> null

            // already reported; the date alone tells the story
            days == 0 -> stringResource(Res.string.profile_earnings_today)

            days == 1 -> stringResource(Res.string.profile_earnings_tomorrow)

            else -> stringResource(Res.string.profile_earnings_in_days, days)
        }
    },
).joinToString("  ·  ").takeIf { it.isNotEmpty() }

@Composable
private fun MomentumCard(detail: TickerDetail) {
    SectionCard(title = stringResource(Res.string.detail_momentum)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MomentumCell("1M", detail.mom1m, Modifier.weight(1f))
            MomentumCell("2M", detail.mom2m, Modifier.weight(1f))
            MomentumCell("3M", detail.mom3m, Modifier.weight(1f))
            MomentumCell("12M", detail.mom12m, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MomentumCell(label: String, fraction: Double?, modifier: Modifier = Modifier) {
    MetricTile(
        label = label,
        value = formatMomentum(fraction),
        valueColor = when {
            fraction == null -> OnSurfaceVariant
            fraction >= 0 -> PositiveGreen
            else -> NegativeRed
        },
        modifier = modifier,
    )
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

    // The verdict as a tinted pill, matching the web's quality chip. On the tinted
    // fill the green is darkened for contrast — the plain green does not clear AA.
    val (fill, content) = when (passes) {
        true -> PositiveTint to PositiveOnTint
        false -> NegativeTint to NegativeRed
        null -> InfoTint to OnSurfaceVariant
    }
    SectionCard(title = stringResource(Res.string.detail_quality_gate)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Pill(text = headline, fill = fill, content = content)
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

/**
 * The universal surface: white, a hairline border, 8dp corners, NO shadow.
 * Borders rather than elevation is the defining choice of this design — it gives
 * the screen the flat, printed quality of a research sheet.
 *
 * The title is the signature 10sp uppercase micro-label.
 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    SectionCard {
        Text(text = title.uppercase(), style = microLabelStyle(), color = OnSurfaceVariant)
        content()
    }
}

/**
 * The card without the standard title line, for the one section that puts its label
 * on the same row as its data (the price header) rather than above it.
 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(Hairline, OutlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color = OnSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        Text(text = value, style = monoStyle(15), color = valueColor)
    }
}

/**
 * A borderless tinted tile — the third level of the surface nesting the web uses:
 * tinted page, white card, tinted tile. Used for the momentum readings.
 */
@Composable
private fun MetricTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceTile, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = microLabelStyle(), color = OnSurfaceVariant)
        // 14sp, not 16: four tiles across a phone leave ~60dp of text width, and a
        // 12-month momentum runs to seven characters ("+135.7%"). At 16sp the
        // percent sign fell off the end — the one character that gives the number
        // its meaning.
        Text(text = value, style = monoStyle(14, FontWeight.Bold), color = valueColor, maxLines = 1)
    }
}

/** A tight, fully-rounded status pill: tinted fill, uppercase micro-label text. */
@Composable
private fun Pill(text: String, fill: Color, content: Color) {
    Text(
        text = text.uppercase(),
        style = microLabelStyle(),
        color = content,
        maxLines = 1,
        modifier = Modifier
            .background(fill, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
