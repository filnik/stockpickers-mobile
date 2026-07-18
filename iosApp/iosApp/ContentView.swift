import SwiftUI
import UIKit
import Shared

/// Hosts the Compose Multiplatform leaders board. On iOS only the LIST is Compose;
/// tapping a row calls back into Swift to push a NATIVE SwiftUI detail (the hybrid).
struct LeadersView: UIViewControllerRepresentable {
    let onTickerSelected: (String) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(onTickerSelected: onTickerSelected)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    /// The native navigation stack. The Compose list appends a ticker here; the
    /// `navigationDestination` renders the native SwiftUI detail for it.
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            LeadersView(onTickerSelected: { ticker in path.append(ticker) })
                // Compose owns its insets — its Scaffold already pads for the status
                // bar and IME. Letting SwiftUI ALSO inset the hosted controller pads
                // the top twice, which showed as a band of dead space above the title.
                .ignoresSafeArea()
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: String.self) { ticker in
                    TickerDetailView(ticker: ticker)
                }
        }
    }
}
