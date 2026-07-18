package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.stockpickers.kmp.domain.PricePoint
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.multiplatform.cartesian.data.lineSeries
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.multiplatform.common.Fill

private val CHART_HEIGHT = 180.dp

/**
 * A line/area chart of daily closes, drawn with Vico. Android only in practice —
 * iOS renders Swift Charts and never reaches this composable, but it compiles for
 * every KMP target (Vico's `multiplatform` artifact is klib-compatible).
 *
 * [points] must be non-empty; the caller shows a placeholder for the empty/absent
 * case. Scroll is disabled so the whole series fits the card width, and the diff
 * animation is off so the chart draws in one frame (nicer for a compact overview
 * and deterministic for screenshot tests). _x_ values are the point indices, which
 * spaces trading days evenly regardless of weekend/holiday gaps.
 */
@Composable
internal fun PriceChart(
    points: List<PricePoint>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        modelProducer.runTransaction { lineSeries { series(points.map { it.close }) } }
    }

    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(lineColor.copy(alpha = 0.18f))),
    )
    // Scale Y to the data range (with a little padding), NOT from 0 — otherwise the
    // area's default baseline drags 0 into the domain and intraday (small moves)
    // looks flat. Recomputed per series.
    val rangeProvider = remember(points) {
        val closes = points.map { it.close }
        val lo = closes.min()
        val hi = closes.max()
        val pad = if (hi == lo) (if (hi == 0.0) 1.0 else hi * 0.02) else (hi - lo) * 0.06
        CartesianLayerRangeProvider.fixed(minY = lo - pad, maxY = hi + pad)
    }
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(line),
            rangeProvider = rangeProvider,
        ),
        startAxis = VerticalAxis.rememberStart(),
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        animationSpec = null,
        animateIn = false,
    )
}
