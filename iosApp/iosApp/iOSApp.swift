import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Must run before the first Compose frame: the screens resolve their
        // ViewModels through koinViewModel().
        MainViewControllerKt.startKoinIos()
        // Hand the shared code its native chart. Optional by design — without this the
        // detail screen still works, it just draws the chart with the shared renderer.
        NativePriceChart.shared.renderer = PriceChartRenderer()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
