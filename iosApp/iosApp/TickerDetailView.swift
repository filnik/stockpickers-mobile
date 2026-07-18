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

    /// Picks the chart's time window on the SHARED ViewModel. `ChartRange` is the
    /// SKIE-bridged Kotlin enum (native Swift enum), so this hands the value straight
    /// through — the ViewModel re-points the observed Room series and refreshes.
    func selectRange(_ r: ChartRange) { viewModel.selectRange(range: r) }
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
                PriceSection(
                    series: model.state.priceSeries,
                    selectedRange: model.state.selectedRange,
                    isLoading: model.state.isChartLoading,
                    onSelectRange: { model.selectRange($0) }
                )
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
/// Money formatting, mirroring `Formatting.kt` in :shared so both platforms read
/// identically: currencies with a widely-read symbol lead with it ("$150.00"), the
/// rest trail their ISO code ("35.55 TWD"). Kept native here because this screen
/// deliberately renders in SwiftUI — the shared layer owns the DATA, not the glyphs.
private func currencySymbol(_ code: String) -> String? {
    switch code.uppercased() {
    case "USD": return "$"
    case "EUR": return "€"
    case "GBP": return "£"
    case "JPY", "CNY": return "¥"
    default: return nil
    }
}

private func formatQuote(_ value: Double, _ currency: String?) -> String {
    let amount = String(format: "%.2f", value)
    guard let code = currency else { return amount }
    if let symbol = currencySymbol(code) { return symbol + amount }
    return "\(amount) \(code)"
}

/// The sign leads the symbol (`-$3.95`, never `$-3.95`), so the magnitude is
/// formatted unsigned.
private func formatSignedQuote(_ value: Double, _ currency: String?) -> String {
    let sign = value >= 0 ? "+" : "-"
    let amount = String(format: "%.2f", Swift.abs(value))
    guard let code = currency else { return sign + amount }
    if let symbol = currencySymbol(code) { return sign + symbol + amount }
    return "\(sign)\(amount) \(code)"
}

private struct PriceSection: View {
    let series: PriceSeries?
    /// The chip currently selected in the shared state (drives the segmented Picker).
    let selectedRange: ChartRange
    /// A refresh for the selected range is in flight — show a soft loader, not "no data".
    let isLoading: Bool
    /// Fires when the user taps a different segment; forwards to the shared ViewModel.
    let onSelectRange: (ChartRange) -> Void

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

    /// The selected window's span, in seconds. Intraday windows (1D/1W) pack a few
    /// days of 5m/30m candles; a month or more spans weeks. Used to pick the X-axis
    /// label granularity (hour vs. month) from the DATA, not from `selectedRange`, so
    /// it stays correct even if a range returns a partial series.
    private var isIntraday: Bool {
        let times = points.map { $0.id }
        guard let lo = times.min(), let hi = times.max() else { return false }
        return (hi - lo) < 8 * 24 * 3600 // < ~8 days → intraday candles
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            rangePicker
            chartArea
        }
        .padding(.vertical, 4)
    }

    /// TradingView-style segmented range selector. Iterates the SKIE-bridged Kotlin
    /// enum via Swift `CaseIterable` (`ChartRange.allCases`, in declaration order
    /// 1D…1Y); each case is `Hashable`, so `\.self` ids and `.tag` work directly.
    private var rangePicker: some View {
        Picker("Range", selection: Binding(
            get: { selectedRange },
            set: { onSelectRange($0) }
        )) {
            ForEach(ChartRange.allCases, id: \.self) { range in
                Text(range.label).tag(range)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }

    /// Last quote + currency, plus the SELECTED PERIOD's change (absolute and %),
    /// coloured by sign. Both figures come from the shared `PriceSeries`
    /// (`periodChange` / `periodChangePercent`), so they update as the range changes.
    @ViewBuilder
    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if let last = series?.last?.doubleValue {
                Text(formatQuote(last, series?.currency))
                    .font(.title2).bold().monospacedDigit()
            } else {
                Text("—").font(.title2).bold().foregroundStyle(.secondary)
            }
            Spacer()
            if let pct = series?.periodChangePercent?.doubleValue {
                let up = pct >= 0
                HStack(spacing: 5) {
                    if let change = series?.periodChange?.doubleValue {
                        Text(formatSignedQuote(change, series?.currency)).monospacedDigit()
                    }
                    Text(String(format: "(%+.2f%%)", pct * 100)).monospacedDigit()
                }
                .font(.subheadline).bold()
                .foregroundStyle(up ? .green : .red)
            }
        }
    }

    /// The chart, or its stand-ins: a soft loader while a freshly-picked range fetches
    /// (so a chip switch never flashes the empty state), and an explicit "no data" once
    /// the load settles with zero points.
    @ViewBuilder
    private var chartArea: some View {
        let pts = points
        if pts.isEmpty {
            ZStack {
                if isLoading {
                    ProgressView()
                } else {
                    Text("No data for this range")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 200)
        } else {
            chart(pts)
                .overlay(alignment: .topTrailing) {
                    if isLoading {
                        ProgressView().controlSize(.small).padding(6)
                    }
                }
        }
    }

    private func chart(_ pts: [ChartPoint]) -> some View {
        let intraday = isIntraday
        // TradingView-style zoomed Y: frame the visible range around the data, never
        // from 0. `.automatic(includesZero: false)` alone is NOT enough here — the
        // AreaMark pins its baseline to 0, which drags 0 back into the auto domain — so
        // we compute an explicit padded [lo, hi] and anchor the area's fill to `lo`.
        let closes = pts.map(\.close)
        let dataLo = closes.min() ?? 0
        let dataHi = closes.max() ?? 1
        let pad = max((dataHi - dataLo) * 0.08, 0.0001)
        let yLo = dataLo - pad
        let yHi = dataHi + pad
        return Chart {
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
                    yStart: .value("Base", yLo),
                    yEnd: .value("Close", point.close)
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
                            Text(sel.date, format: intraday
                                 ? .dateTime.month(.abbreviated).day().hour().minute()
                                 : .dateTime.day().month(.abbreviated).year())
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
        .chartYScale(domain: yLo...yHi)
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
                // Intraday windows (1D/1W) read as clock time; longer ones as month.
                if intraday {
                    AxisValueLabel(format: .dateTime.hour())
                } else {
                    AxisValueLabel(format: .dateTime.month(.abbreviated))
                }
            }
        }
        .frame(height: 200)
    }
}
