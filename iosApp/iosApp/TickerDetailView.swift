import SwiftUI
import Charts
import Shared

/// Observes the SHARED Kotlin `TickerDetailViewModel` and republishes its state to
/// SwiftUI. SKIE exposes the ViewModel's `StateFlow` as a native Swift
/// `AsyncSequence`, so this iterates it directly — no hand-written bridge. The
/// `for await` Task is cancelled in `.onDisappear`, which drops the last subscriber
/// and lets the ViewModel's `WhileSubscribed` tear the Room flow down.
@MainActor
final class TickerDetailModel: ObservableObject {
    @Published var state: TickerDetailUiState
    private let viewModel: TickerDetailViewModel
    private var task: Task<Void, Never>?

    init(ticker: String) {
        let vm = IosViewModelsKt.tickerDetailViewModel(ticker: ticker)
        self.viewModel = vm
        self.state = vm.uiState.value
        task = Task { [weak self] in
            for await newState in vm.uiState {
                self?.state = newState
            }
        }
    }

    /// Stop observing the shared StateFlow. Called from `.onDisappear`.
    func stop() { task?.cancel() }
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

            Section("Price") {
                PriceSection(series: model.state.priceSeries)
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

/// One drawable close, mapped out of the shared Kotlin `PricePoint` into native
/// Swift types so Swift Charts (which can't plot Obj-C-bridged classes directly on
/// a `Date` axis) has a clean `Date`/`Double` pair. `id` is the timestamp: trading
/// days are unique, and it makes the scrubber's nearest-point lookup trivial.
private struct ChartPoint: Identifiable {
    let id: TimeInterval
    let date: Date
    let close: Double
}

/// NATIVE Swift Charts price chart, fed by the SAME shared `PriceSeries` the Kotlin
/// ViewModel exposes via SKIE. Line + gradient area of daily closes, green/red by
/// whether the last quote is above the previous close, with an interactive scrubber
/// (`.chartXSelection`) that drops a lollipop on the nearest day.
private struct PriceSection: View {
    let series: PriceSeries?

    /// Bound to `.chartXSelection`; the x-value (a `Date`) under the user's finger.
    @State private var selectedDate: Date?

    /// Maps the Kotlin `List<PricePoint>` into Swift. `series.points` bridges to
    /// `[PricePoint]` (an `NSArray` of SKIE-exported objects), so a plain `for`
    /// loop is enough — no `as?` cast needed. `epochSeconds` is a non-null `Int64`
    /// and `close` a non-null `Double`, so neither needs `.doubleValue` unboxing
    /// (that's only for the `KotlinDouble?` fields `last`/`previousClose`).
    private var points: [ChartPoint] {
        guard let series else { return [] }
        var result: [ChartPoint] = []
        for p in series.points {
            let t = TimeInterval(p.epochSeconds)
            result.append(ChartPoint(id: t, date: Date(timeIntervalSince1970: t), close: p.close))
        }
        return result
    }

    /// Green when the latest quote holds at/above the previous close, else red.
    /// Defaults to green when either quote is missing (a flat/unknown series).
    private var isUp: Bool {
        guard let last = series?.last?.doubleValue,
              let prev = series?.previousClose?.doubleValue else { return true }
        return last >= prev
    }

    private var tint: Color { isUp ? .green : .red }

    /// The point nearest the scrubbed x-position, for the lollipop.
    private var selectedPoint: ChartPoint? {
        guard let selectedDate, !points.isEmpty else { return nil }
        let target = selectedDate.timeIntervalSince1970
        return points.min { abs($0.id - target) < abs($1.id - target) }
    }

    var body: some View {
        let pts = points
        if pts.isEmpty {
            Text("Chart unavailable")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
        } else {
            VStack(alignment: .leading, spacing: 10) {
                header
                chart(pts)
            }
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private var header: some View {
        if let last = series?.last?.doubleValue {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(String(format: "%.2f", last))
                    .font(.title2).bold().monospacedDigit()
                if let currency = series?.currency {
                    Text(currency)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let prev = series?.previousClose?.doubleValue, prev != 0 {
                    let change = (last - prev) / prev * 100
                    Text(String(format: "%+.2f%%", change))
                        .font(.subheadline).bold().monospacedDigit()
                        .foregroundStyle(tint)
                }
            }
        }
    }

    private func chart(_ pts: [ChartPoint]) -> some View {
        Chart {
            ForEach(pts) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Close", point.close)
                )
                .foregroundStyle(tint)
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 2))

                AreaMark(
                    x: .value("Date", point.date),
                    y: .value("Close", point.close)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [tint.opacity(0.28), tint.opacity(0.02)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .interpolationMethod(.monotone)
            }

            // Interactive scrubber: rule + lollipop + dot on the selected day.
            if let sel = selectedPoint {
                RuleMark(x: .value("Selected", sel.date))
                    .foregroundStyle(Color.secondary.opacity(0.4))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .annotation(
                        position: .top,
                        spacing: 0,
                        overflowResolution: .init(x: .fit(to: .chart), y: .disabled)
                    ) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sel.date, format: .dateTime.day().month(.abbreviated).year())
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(String(format: "%.2f", sel.close))
                                .font(.caption).bold().monospacedDigit()
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                    }

                PointMark(
                    x: .value("Date", sel.date),
                    y: .value("Close", sel.close)
                )
                .foregroundStyle(tint)
                .symbolSize(70)
            }
        }
        .chartXSelection(value: $selectedDate)
        .chartYScale(domain: .automatic(includesZero: false))
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                AxisGridLine()
                AxisTick()
                AxisValueLabel()
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) {
                AxisGridLine()
                AxisValueLabel(format: .dateTime.month(.abbreviated))
            }
        }
        .frame(height: 200)
    }
}
