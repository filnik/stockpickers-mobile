import SwiftUI
import Shared

/// Observes the SHARED Kotlin `TickerDetailViewModel` (via `TickerDetailBridge`)
/// and republishes its state to SwiftUI. This is the manual Flow->Swift bridge that
/// SKIE would otherwise generate — SKIE 0.10.13 doesn't support Kotlin 2.4.10 yet.
@MainActor
final class TickerDetailModel: ObservableObject {
    @Published var state: TickerDetailUiState
    private let bridge: TickerDetailBridge

    init(ticker: String) {
        let bridge = TickerDetailBridge(ticker: ticker)
        self.bridge = bridge
        self.state = bridge.current
        bridge.observe { [weak self] newState in
            self?.state = newState
        }
    }

    /// Stop collecting the shared StateFlow. Called from `.onDisappear`.
    func stop() { bridge.cancel() }
}

/// NATIVE SwiftUI detail screen. Same shared ViewModel as the Compose (Android)
/// detail — different UI. Inset-grouped List, native navigation bar.
struct TickerDetailView: View {
    let ticker: String
    @StateObject private var model: TickerDetailModel

    init(ticker: String) {
        self.ticker = ticker
        _model = StateObject(wrappedValue: TickerDetailModel(ticker: ticker))
    }

    var body: some View {
        content
            .navigationTitle(ticker)
            .navigationBarTitleDisplayMode(.large)
            .onDisappear { model.stop() }
    }

    @ViewBuilder
    private var content: some View {
        if let d = model.state.detail {
            detailList(d)
        } else if model.state.isMissing {
            ContentUnavailableView(
                "Not in cache",
                systemImage: "tray",
                description: Text("This ticker isn't in the last synced snapshot.")
            )
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func detailList(_ d: TickerDetail) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(d.name ?? d.ticker).font(.title2).bold()
                    Text([d.country, d.sector].compactMap { $0 }.joined(separator: " · "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section("Momentum") {
                momentumRow("1M", d.mom1m)
                momentumRow("2M", d.mom2m)
                momentumRow("3M", d.mom3m)
                momentumRow("12M", d.mom12m)
            }

            Section("Trend quality") {
                metricRow("Clenow score", decimal(d.clenow))
                metricRow("R² (fit)", decimal(d.r2))
            }

            Section("Fundamentals") {
                metricRow("Forward P/E", decimal(d.forwardPe))
                metricRow("PEG", decimal(d.peg))
                metricRow("ROIC", percent(d.roic))
            }

            Section("Quality gate") {
                qualityRow(d.qualityGate)
            }

            if let updated = d.updatedAt {
                Section {
                    Text("Pipeline updated \(updated)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func momentumRow(_ label: String, _ value: KotlinDouble?) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            if let v = value?.doubleValue {
                Text(String(format: "%+.1f%%", v * 100))
                    .foregroundStyle(v >= 0 ? .green : .red)
                    .monospacedDigit()
                    .bold()
            } else {
                Text("—").foregroundStyle(.secondary)
            }
        }
    }

    private func metricRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit()
        }
    }

    @ViewBuilder
    private func qualityRow(_ gate: QualityGate?) -> some View {
        if gate?.passesFilters?.boolValue == true {
            Label("Passes quality filters", systemImage: "checkmark.seal.fill")
                .foregroundStyle(.green)
        } else if gate?.passesFilters?.boolValue == false {
            VStack(alignment: .leading, spacing: 2) {
                Label("Excluded by filters", systemImage: "xmark.seal.fill")
                    .foregroundStyle(.red)
                if let reason = gate?.reason {
                    Text(reason).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } else {
            Label("Not evaluated", systemImage: "questionmark.circle")
                .foregroundStyle(.secondary)
        }
    }

    private func decimal(_ v: KotlinDouble?) -> String {
        guard let d = v?.doubleValue else { return "—" }
        return String(format: "%.2f", d)
    }

    private func percent(_ v: KotlinDouble?) -> String {
        guard let d = v?.doubleValue else { return "—" }
        return String(format: "%.1f%%", d * 100)
    }
}
