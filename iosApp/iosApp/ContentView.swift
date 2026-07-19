import SwiftUI
import UIKit
import Shared

/// Hosts the shared Compose app — every screen, and its Nav3 back stack.
///
/// There is no SwiftUI `NavigationStack` here: navigation lives in Kotlin, so the two
/// platforms cannot drift out of step. The only native thing on the detail screen is
/// the chart, and it is injected the other way round — see `PriceChartRenderer`.
struct AppHostView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        AppHostView()
            // Compose owns its insets — its Scaffold already pads for the status bar
            // and IME. Letting SwiftUI ALSO inset the hosted controller pads the top
            // twice, which showed as a band of dead space above the title.
            .ignoresSafeArea()
    }
}
