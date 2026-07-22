package app.stockpickers.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stockpickers.kmp.domain.PricePoint

/**
 * The price chart, drawn by whatever each platform draws charts best with: Vico on
 * Android, Swift Charts on iOS.
 *
 * This is the ONLY thing on the detail screen that is platform-specific. Everything
 * around it — layout, cards, strings, the range selector, the ViewModel — is shared
 * Compose on both platforms. The chart earns the exception because a chart is where
 * the platform toolkit genuinely wins: Swift Charts brings scrubbing, accessibility
 * and rendering that would have to be rebuilt by hand otherwise.
 *
 * The parameter is [positive], not a colour. Each platform then applies its OWN
 * palette to the same semantic fact, instead of one platform's pixels leaking into
 * the other. Passing a `Color` would also mean converting it across the Obj-C bridge
 * for no gain.
 */
@Composable
internal expect fun PlatformPriceChart(points: List<PricePoint>, positive: Boolean, modifier: Modifier = Modifier)
