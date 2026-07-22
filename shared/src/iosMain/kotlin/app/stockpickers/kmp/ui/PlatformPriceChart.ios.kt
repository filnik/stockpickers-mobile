package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import app.stockpickers.kmp.domain.PricePoint
import platform.UIKit.UIViewController

/**
 * Draws one price series. Implemented in SWIFT, over Swift Charts, and handed down
 * to Kotlin at startup.
 *
 * The dependency has to be inverted like this: the SwiftUI view lives in the Xcode
 * target, which depends on this framework, so the framework cannot reference it. What
 * it can do is declare the shape it needs and let Swift fill it in.
 *
 * Two methods rather than one because the controller must SURVIVE recomposition.
 * Rebuilding it on every data change would restart the chart — losing the scrub
 * selection and re-running its entry animation each time the range chip is tapped.
 */
interface NativePriceChartRenderer {
    /** Called once per chart instance. */
    fun makeController(): UIViewController

    /** Called on every recomposition with changed data. Must mutate, not rebuild. */
    fun update(controller: UIViewController, points: List<PricePoint>, positive: Boolean)
}

/**
 * Where Swift plugs its chart in, from `iOSApp.init`.
 *
 * Deliberately a plain nullable var and not a Koin binding: this is set by the app
 * shell before Koin's graph is even used, and making it optional is what lets the
 * composable below degrade instead of crash (see there).
 */
object NativePriceChart {
    var renderer: NativePriceChartRenderer? = null
}

/**
 * iOS renders through [NativePriceChart.renderer] — falling back to the shared Vico
 * chart when Swift has not registered one.
 *
 * That fallback is the point: an inverted dependency normally leaves a hole that only
 * the app can fill, and forgetting to fill it fails at RUNTIME. Here forgetting costs
 * a less-native chart and nothing else. The feature can never be missing, only
 * plainer — which is the right way round for a UI detail.
 */
@Composable
internal actual fun PlatformPriceChart(points: List<PricePoint>, positive: Boolean, modifier: Modifier) {
    val renderer = NativePriceChart.renderer
    if (renderer == null) {
        PriceChart(
            points = points,
            lineColor = if (positive) PositiveGreen else NegativeRed,
            modifier = modifier,
        )
        return
    }
    UIKitViewController(
        factory = { renderer.makeController() },
        modifier = modifier.fillMaxWidth().height(ChartHeight),
        update = { controller -> renderer.update(controller, points, positive) },
        // COOPERATIVE, not the default: the chart sits inside a vertically scrolling
        // Compose column, and a native view normally swallows every touch that lands
        // on it — so the 200dp the chart occupies became dead to scrolling. This gives
        // Compose the first look for a moment: a flick scrolls the page, a press that
        // lingers goes through to the chart's scrubber. Same bargain iOS itself strikes
        // for interactive content inside a scroll view.
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.Cooperative(),
        ),
    )
}
