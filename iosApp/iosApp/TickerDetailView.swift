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
                VStack(alignment: .leading, spacing: 8) {
                    Text(d.name ?? d.ticker)
                        .font(.title2).bold()
                        .foregroundStyle(Palette.primary)
                    HStack(spacing: 6) {
                        ForEach([d.country, d.sector].compactMap { $0 }, id: \.self) { label in
                            MicroLabel(text: label)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .overlay(Capsule().stroke(Palette.border, lineWidth: 1))
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            Section(header: MicroLabel(text: "Price")) {
                PriceSection(
                    series: model.state.priceSeries,
                    selectedRange: model.state.selectedRange,
                    isLoading: model.state.isChartLoading,
                    onSelectRange: { model.selectRange($0) }
                )
            }

            // Absent for most tickers: upstream only writes a profile for the names
            // its research pass has covered.
            if let profile = model.state.profile {
                profileSection(profile)
            }

            Section(header: MicroLabel(text: "Momentum")) {
                momentumRow("1M", d.mom1m)
                momentumRow("2M", d.mom2m)
                momentumRow("3M", d.mom3m)
                momentumRow("12M", d.mom12m)
            }

            Section(header: MicroLabel(text: "Trend quality")) {
                metricRow("Clenow score", decimal(d.clenow))
                metricRow("R² (fit)", decimal(d.r2))
            }

            Section(header: MicroLabel(text: "Fundamentals")) {
                metricRow("Forward P/E", decimal(d.forwardPe))
                metricRow("PEG", decimal(d.peg))
                metricRow("ROIC", percent(d.roic))
            }

            Section(header: MicroLabel(text: "Quality gate")) {
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
        .scrollContentBackground(.hidden)
        .background(Palette.page)
        .listRowBackground(Palette.card)
        .listStyle(.insetGrouped)
    }

    /// The written profile — the SwiftUI twin of `ProfileCard` in TickerDetailScreen.kt.
    ///
    /// Two badges sit at the top and they say different kinds of thing. Freshness is a
    /// property of the DATA (how old the text is against its own TTL) and is omitted
    /// when unknowable — an absent badge is honest, a guessed one is not. The language
    /// badge is a property of the PRODUCT: this text is always Italian while the UI may
    /// not be, and naming that beats leaving an English reader to wonder.
    ///
    /// Strings are hardcoded here rather than read from composeResources — see the note
    /// on this file's other labels; the iOS detail screen is deliberately native.
    @ViewBuilder
    private func profileSection(_ profile: TickerProfile) -> some View {
        Section(header: MicroLabel(text: "Company profile")) {
            HStack(spacing: 6) {
                switch profile.freshness {
                case .fresh:
                    badge("Up to date", fill: Palette.positive.opacity(0.12), tint: Palette.positive)
                case .stale:
                    badge("May be outdated", fill: Palette.warn.opacity(0.18), tint: Palette.warn)
                // No badge at all: we cannot date this text, so we claim nothing.
                case .unknown: EmptyView()
                }
                badge("In Italian", fill: Palette.navy.opacity(0.08), tint: Palette.muted)
            }

            if let timeless = profile.timelessDescription {
                Text(timeless).foregroundStyle(Palette.primary)
            }
            if let current = profile.currentDescription {
                Text(current).font(.footnote).foregroundStyle(.secondary)
            }

            if !profile.pros.isEmpty {
                argumentList("For", profile.pros, tint: Palette.positive)
            }
            if !profile.cons.isEmpty {
                argumentList("Against", profile.cons, tint: Palette.negative)
            }

            if let earnings = profile.nextEarnings {
                VStack(alignment: .leading, spacing: 4) {
                    MicroLabel(text: "Next results")
                    // Three stacked pieces, not one line. `consensus` reads like a
                    // rating but is a paragraph of prose upstream — inlining it after
                    // the date would run a 300-character sentence through a monospaced
                    // one-liner.
                    if let when_ = earningsWhen(earnings) {
                        Text(when_)
                            .font(.system(.footnote, design: .monospaced).weight(.semibold))
                            .foregroundStyle(Palette.primary)
                    }
                    if let consensus = earnings.consensus, !consensus.isEmpty {
                        Text(consensus).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    /// One side of the argument. The tint lives on the BULLET, not the prose: a
    /// paragraph of coloured text is hard to read, while a coloured marker carries the
    /// same for/against signal at a glance.
    private func argumentList(_ title: String, _ items: [String], tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(0.5)
                .foregroundStyle(tint)
            ForEach(items, id: \.self) { item in
                HStack(alignment: .top, spacing: 8) {
                    Circle().fill(tint).frame(width: 5, height: 5).padding(.top, 6)
                    Text(item).font(.footnote).foregroundStyle(Palette.primary)
                }
            }
        }
    }

    private func badge(_ text: String, fill: Color, tint: Color) -> some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(0.5)
            .foregroundStyle(tint)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(fill))
    }

    /// The factual half of the earnings block: "2026-08-05 · in 12 days", or nil.
    ///
    /// The countdown is only ever present when the shared layer could compute it from
    /// the date — it refuses to replay upstream's frozen one — so there is no judgement
    /// to make here about whether to trust the number.
    private func earningsWhen(_ earnings: NextEarnings) -> String? {
        var parts: [String] = []
        if let date = earnings.date { parts.append(date) }
        // Int? crosses the Obj-C bridge boxed, like every other Kotlin primitive.
        if let days = earnings.daysAway?.intValue {
            switch days {
            case 0: parts.append("today")
            case 1: parts.append("tomorrow")
            case let d where d > 1: parts.append("in \(d) days")
            default: break // already reported; the date alone tells the story
            }
        }
        return parts.isEmpty ? nil : parts.joined(separator: "  ·  ")
    }

    private func momentumRow(_ label: String, _ value: KotlinDouble?) -> some View {
        HStack {
            Text(label).foregroundStyle(Palette.muted)
            Spacer()
            if let v = value?.doubleValue {
                Text(String(format: "%+.1f%%", v * 100))
                    .foregroundStyle(v >= 0 ? Palette.positive : Palette.negative)
                    .font(.system(.body, design: .monospaced).weight(.semibold))
            } else {
                Text("—").foregroundStyle(Palette.muted)
            }
        }
    }

    private func metricRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(Palette.muted)
            Spacer()
            Text(value)
                .font(.system(.body, design: .monospaced).weight(.semibold))
                .foregroundStyle(Palette.primary)
        }
    }

    @ViewBuilder
    private func qualityRow(_ gate: QualityGate?) -> some View {
        if gate?.passesFilters?.boolValue == true {
            Label("Passes quality filters", systemImage: "checkmark.seal.fill")
                .foregroundStyle(Palette.positive)
        } else if gate?.passesFilters?.boolValue == false {
            VStack(alignment: .leading, spacing: 2) {
                Label("Excluded by filters", systemImage: "xmark.seal.fill")
                    .foregroundStyle(Palette.negative)
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
/// Swift types.
///
/// `id` is the point's POSITION in the series, and it is what the chart plots on x —
/// deliberately NOT the timestamp. On a real time axis the hours a market is shut
/// (overnight, weekends) contain no points, so the line is drawn straight across
/// them: on a 1W intraday window that interpolation is most of the width, and it
/// reads as price action that never happened. Plotting by index collapses closed
/// time the way TradingView does, and matches the Android chart, which is built the
/// same way. `date` is kept for the axis labels and the scrubber readout.
private struct ChartPoint: Identifiable {
    let id: Int
    let date: Date
    let close: Double
}

/// NATIVE Swift Charts price chart, fed by the SAME shared `PriceSeries` the Kotlin
/// ViewModel exposes via SKIE. Line + gradient area of daily closes, green/red by
/// whether the last quote is above the previous close, with an interactive scrubber
/// (`.chartXSelection`) that drops a lollipop on the nearest day.
/// The design tokens, mirroring `Theme.kt` in :shared. There is no dark theme —
/// the product ships a single light scheme, so these are plain constants.
///
/// The list itself stays a native SwiftUI `List`: fighting the platform to
/// reproduce Compose's card stack would cost the free behaviour (scroll physics,
/// swipe, Dynamic Type) and gain nothing. It is tinted, not rebuilt.
private enum Palette {
    static let page = Color(red: 0.976, green: 0.976, blue: 1.0)      // #f9f9ff
    static let card = Color.white
    static let tile = Color(red: 0.906, green: 0.933, blue: 1.0)      // #e7eeff
    static let border = Color(red: 0.769, green: 0.776, blue: 0.812)  // #c4c6cf
    static let primary = Color(red: 0.0, green: 0.055, blue: 0.141)   // #000e24
    static let navy = Color(red: 0.008, green: 0.141, blue: 0.282)    // #022448
    static let muted = Color(red: 0.263, green: 0.278, blue: 0.306)   // #43474e
    static let positive = Color(red: 0.0, green: 0.541, blue: 0.239)  // #008a3d
    static let negative = Color(red: 0.729, green: 0.102, blue: 0.102) // #ba1a1a
    static let warn = Color(red: 0.604, green: 0.435, blue: 0.118)     // #9a6f1e
}

/// The signature 10pt uppercase micro-label used above every block of data.
private struct MicroLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(0.5)
            .foregroundStyle(Palette.muted)
    }
}

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

    /// Bound to `.chartXSelection`; the x-value (a point INDEX) under the user's finger.
    @State private var selectedIndex: Int?

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
            result.append(
                ChartPoint(id: result.count, date: Date(timeIntervalSince1970: t), close: p.close)
            )
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

    private var tint: Color { isUp ? Palette.positive : Palette.negative }

    /// The point under the scrubber. x is an index, so this is a direct lookup —
    /// clamped because the selection can settle just past either end.
    private var selectedPoint: ChartPoint? {
        guard let selectedIndex, !points.isEmpty else { return nil }
        return points[min(max(selectedIndex, 0), points.count - 1)]
    }

    /// The window's span in seconds, read from the DATA rather than `selectedRange`,
    /// so it stays right even when a range comes back partial.
    private var spanSeconds: TimeInterval {
        guard let first = points.first?.date, let last = points.last?.date else { return 0 }
        return last.timeIntervalSince(first)
    }

    private var isIntraday: Bool { spanSeconds < 8 * 24 * 3600 }

    /// X labels sized to the window. A bare hour is right for a single session but
    /// useless across a week (every automatic tick lands at midnight and reads "00"),
    /// and a bare month repeats itself on short windows — so each span gets the
    /// coarsest field that still distinguishes its labels.
    private var xLabelFormatter: DateFormatter {
        let day: TimeInterval = 86_400
        let span = spanSeconds
        let formatter = DateFormatter()
        formatter.locale = .current
        switch span {
        case ..<(2 * day): formatter.setLocalizedDateFormatFromTemplate("Hm")    // 15:30
        case ..<(8 * day): formatter.setLocalizedDateFormatFromTemplate("EEE")   // Mon
        case ..<(100 * day): formatter.setLocalizedDateFormatFromTemplate("dMMM") // 16 Jun
        default: formatter.setLocalizedDateFormatFromTemplate("MMM")             // Jun
        }
        return formatter
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
                .foregroundStyle(up ? Palette.positive : Palette.negative)
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
        // ~5 labels, always on real point indices so every tick has a date to show.
        // Starting HALF A STEP IN keeps the last one readable: a label centred on the
        // final point has only half a slot of width and gets clipped by the cell edge.
        // Mirrors the same `offset` on the Compose chart — verified there that plot
        // padding alone was not enough, the label was still being cut short.
        let labelStride = max(pts.count / 5, 1)
        let labelIndices = Array(stride(from: labelStride / 2, to: pts.count, by: labelStride))
        let labelFormatter = xLabelFormatter
        return Chart {
            ForEach(pts) { point in
                LineMark(
                    x: .value("Point", point.id),
                    y: .value("Close", point.close)
                )
                .foregroundStyle(tint)
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 2))

                AreaMark(
                    x: .value("Point", point.id),
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
                RuleMark(x: .value("Selected", sel.id))
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
                    x: .value("Point", sel.id),
                    y: .value("Close", sel.close)
                )
                .foregroundStyle(tint)
                .symbolSize(70)
            }
        }
        .chartXSelection(value: $selectedIndex)
        .chartYScale(domain: yLo...yHi)
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                AxisGridLine()
                AxisTick()
                AxisValueLabel()
            }
        }
        .chartXAxis {
            AxisMarks(values: labelIndices) { value in
                AxisGridLine()
                // x is an index, so the label has to be looked up on the point itself.
                if let i = value.as(Int.self), i >= 0, i < pts.count {
                    AxisValueLabel { Text(labelFormatter.string(from: pts[i].date)) }
                }
            }
        }
        // Inset the plot so the labels on the FIRST and LAST ticks have somewhere to
        // go. Without this the outer half of the last label is clipped by the list
        // cell, which reaches the screen edge. Mirrors `addExtremeLabelPadding` on the
        // Compose chart — same problem, same remedy, deliberately the same magnitude.
        .chartPlotStyle { $0.padding(.horizontal, 12) }
        .frame(height: 200)
    }
}
