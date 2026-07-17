package app.stockpickers.kmp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import app.stockpickers.kmp.di.initKoin
import app.stockpickers.kmp.ui.MomentumLeadersScreen
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/**
 * Koin entry point for the iOS app. Called from `iOSApp.swift` at startup.
 *
 * Deliberately NOT named `initKoin*`: the Kotlin/Native Objective-C exporter
 * treats a leading `init` as the ObjC initializer family and renames the symbol
 * to `doInitKoinIos()`. `startKoinIos` crosses the bridge unmangled.
 *
 * Idempotent: a second `startKoin` would throw KoinAppAlreadyStartedException.
 */
fun startKoinIos() {
    if (KoinPlatform.getKoinOrNull() == null) {
        initKoin()
    }
}

/**
 * The Compose leaders board, hosted by SwiftUI through `UIViewControllerRepresentable`.
 *
 * HYBRID ARCHITECTURE: on iOS only the LIST is Compose Multiplatform; the detail
 * screen is native SwiftUI (see `TickerDetailView.swift`). So this controller hosts
 * a single Compose screen, not the Nav3 back stack — tapping a row calls back into
 * Swift via [onTickerSelected], which drives a native `NavigationStack`. Android
 * keeps the full Nav3 stack in Compose (see `StockpickersRoot`).
 */
fun MainViewController(onTickerSelected: (String) -> Unit): UIViewController =
    ComposeUIViewController {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                MomentumLeadersScreen(onTickerClick = onTickerSelected)
            }
        }
    }
