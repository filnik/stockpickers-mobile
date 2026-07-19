package app.stockpickers.kmp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stockpickers.kmp.ui.navigation.Nav3Host
import app.stockpickers.kmp.ui.navigation.rememberNavigator

/**
 * The single root of the UI, shared by MainActivity and MainViewController.
 *
 * Theme + navigation live here rather than being rebuilt per platform, so the two
 * entry points cannot drift apart. Each platform is left with only what is
 * genuinely its own (edge-to-edge on Android, the UIViewController wrapper on iOS).
 */
@Composable
fun StockpickersRoot(modifier: Modifier = Modifier) {
    StockpickersTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Nav3Host(navigator = rememberNavigator())
        }
    }
}
