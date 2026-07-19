package app.stockpickers.kmp

import androidx.compose.ui.window.ComposeUIViewController
import app.stockpickers.kmp.di.initKoin
import app.stockpickers.kmp.ui.StockpickersRoot
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
 * The WHOLE app, hosted by SwiftUI through `UIViewControllerRepresentable`.
 *
 * Both platforms run the same [StockpickersRoot]: same theme, same Nav3 back stack,
 * same screens. iOS keeps exactly ONE native seam — the price chart, which is Swift
 * Charts injected through `NativePriceChart` — because a chart is the one place the
 * platform toolkit clearly wins.
 *
 * This replaced a hybrid where the detail screen was a whole native SwiftUI screen
 * driven by the shared ViewModel. That version demonstrated more interop, but it cost
 * two navigation stacks to keep in step (Nav3 here, `NavigationStack` there) and a
 * second copy of every screen and every string. One seam instead of one screen.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { StockpickersRoot() }
