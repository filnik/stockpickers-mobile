package app.stockpickers.kmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Composables shared by more than one screen.
//
// Deliberately small: with two screens, most UI belongs next to the screen that
// renders it. Something earns a place here once a SECOND screen needs it — and then
// it MOVES here rather than being copied.

/** Centres [content] in all available space. */
@Composable
internal fun CenteredFill(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The app's one segmented control: a rounded track with a filled thumb on the
 * selected item.
 *
 * There is exactly one implementation on purpose. The board's sort tabs and the
 * detail chart's range picker had grown two copies that differed only by a
 * millimetre of padding — the drift a comment on one of them was already warning
 * against. The dimensional differences that remain are real (the chart's picker
 * sits inside a card and is tighter), so they are PARAMETERS rather than a second
 * copy: changing the shape now changes both.
 */
@Composable
internal fun <T> SegmentedControl(
    items: List<T>,
    selected: T,
    // @Composable because the labels come from stringResource — the board's tabs are
    // localised, so this cannot be a plain lambda.
    label: @Composable (T) -> String,
    textStyle: TextStyle,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    trackPadding: Dp = SegmentedTrackPadding,
    itemSpacing: Dp = SegmentedItemSpacing,
    itemVerticalPadding: Dp = SegmentedItemVerticalPadding,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(outerPadding)
            .background(SurfaceTile, CircleShape)
            .padding(trackPadding),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        items.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) PrimaryContainer else Color.Transparent, CircleShape)
                    .clickable { onSelect(item) }
                    .padding(vertical = itemVerticalPadding),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = label(item),
                    style = textStyle,
                    color = if (active) Color.White else OnSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

internal val SegmentedTrackPadding = 4.dp
internal val SegmentedItemSpacing = 4.dp
internal val SegmentedItemVerticalPadding = 8.dp

/**
 * The app's busy spinners, drawn STILL when [LocalInspectionMode] is on — previews
 * and Roborazzi snapshots.
 *
 * Material's indeterminate indicators run an `InfiniteTransition`. Under Robolectric
 * that is not merely pointless but ruinous: the shadow Choreographer "advances the
 * clock by the frame delay every time a frame callback is added", so the animation
 * schedules the next frame, which advances the clock, which schedules the next —
 * a loop that feeds itself. Measured on this project: minutes per screenshot for the
 * four loading states, against ~0.1s for every static one.
 *
 * The substitute is the DETERMINATE overload at a fixed fraction, not a hand-drawn
 * shape: it is the real Material component with the real geometry and colours, just
 * with nothing left to animate. A single captured frame could never show motion
 * anyway — this only stops it pretending otherwise.
 *
 * The check belongs HERE, in the component, and not in the test harness. Pausing the
 * frame clock from the test was tried and does not work: by the time a test can act,
 * the animation has already registered its callbacks. The composable is the only
 * place that knows before the first frame.
 */
@Composable
internal fun BusyCircularIndicator(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        CircularProgressIndicator(progress = { StillProgressFraction }, modifier = modifier)
    } else {
        CircularProgressIndicator(modifier)
    }
}

/** The linear counterpart of [BusyCircularIndicator]; same reasoning. */
@Composable
internal fun BusyLinearIndicator(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        LinearProgressIndicator(progress = { StillProgressFraction }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier)
    }
}

/** Two-thirds along: unmistakably "in progress", and not confusable with done. */
private const val StillProgressFraction = 0.66f
