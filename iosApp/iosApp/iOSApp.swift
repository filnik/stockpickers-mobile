import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Must run before the first Compose frame: MomentumLeadersScreen resolves
        // its ViewModel through koinViewModel().
        MainViewControllerKt.startKoinIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
