package app.stockpickers.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stockpickers.kmp.domain.PricePoint

/** Android draws with Vico, which is already a Compose composable — nothing to bridge. */
@Composable
internal actual fun PlatformPriceChart(
    points: List<PricePoint>,
    positive: Boolean,
    modifier: Modifier,
) {
    PriceChart(
        points = points,
        lineColor = if (positive) PositiveGreen else NegativeRed,
        modifier = modifier,
    )
}
