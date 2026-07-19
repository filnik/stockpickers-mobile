import SwiftUI
import Charts
import UIKit
import Shared

/// The ONE native seam in this app.
///
/// Everything else on the detail screen — layout, cards, strings, the range selector —
/// is shared Compose running on both platforms. The chart is Swift Charts because a
/// chart is where the platform toolkit genuinely wins: scrubbing, hit-testing and
/// accessibility that would otherwise have to be rebuilt by hand.
///
/// The dependency runs UPWARDS: the shared framework declares
/// `NativePriceChartRenderer` and this file implements it, because the framework
/// cannot see the Xcode target. Registration happens in `iOSApp.init`.

// MARK: - Model

/// One drawable close. `id` is the point INDEX, not the timestamp: plotting by index
/// collapses nights and weekends, so a gap in trading is not drawn as a gap in price
/// (the same thing TradingView does, and what the Compose chart does too).
private struct ChartPoint: Identifiable {
    let id: Int
    let date: Date
    let close: Double
}

/// Holds what the chart draws. An `ObservableObject` rather than plain `View` state so
/// Kotlin can push new data into a controller that ALREADY EXISTS — rebuilding the
/// controller on every range change would restart the chart and drop the scrub.
private final class PriceChartModel: ObservableObject {
    @Published var points: [ChartPoint] = []
    @Published var positive: Bool = true
}

// MARK: - View

private struct PriceChartView: View {
    @ObservedObject var model: PriceChartModel
    @State private var selectedIndex: Int?

    private var tint: Color { model.positive ? Palette.positive : Palette.negative }

    private var selectedPoint: ChartPoint? {
        guard let i = selectedIndex else { return nil }
        return model.points.first { $0.id == i }
    }

    /// Seconds covered by the series — the granularity of the x labels follows from it.
    private var spanSeconds: TimeInterval {
        guard let first = model.points.first, let last = model.points.last else { return 0 }
        return last.date.timeIntervalSince(first.date)
    }

    /// Label granularity from the window's actual span, not from a range flag: a clock
    /// time is right for one session but repeats every day across a week; a bare month
    /// repeats itself on short windows ("Jun Jun Jun"). Mirrors the Compose formatter.
    private var xLabelFormatter: DateFormatter {
        let day: TimeInterval = 86_400
        let formatter = DateFormatter()
        formatter.locale = .current
        switch spanSeconds {
        case ..<(2 * day): formatter.setLocalizedDateFormatFromTemplate("Hm")     // 15:30
        case ..<(8 * day): formatter.setLocalizedDateFormatFromTemplate("EEE")    // Mon
        case ..<(100 * day): formatter.setLocalizedDateFormatFromTemplate("dMMM") // 16 Jun
        default: formatter.setLocalizedDateFormatFromTemplate("MMM")              // Jun
        }
        return formatter
    }

    var body: some View {
        let pts = model.points
        if pts.isEmpty {
            Color.clear
        } else {
            chart(pts)
        }
    }

    private func chart(_ pts: [ChartPoint]) -> some View {
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
        // ~5 labels on real point indices. Starting HALF A STEP IN keeps the last one
        // readable: a label centred on the final point has only half a slot of width
        // and gets clipped by the edge. Mirrors the same `offset` on the Compose chart.
        let labelStride = max(pts.count / 5, 1)
        let labelIndices = Array(stride(from: labelStride / 2, to: pts.count, by: labelStride))
        let labelFormatter = xLabelFormatter

        return Chart {
            ForEach(pts) { point in
                LineMark(x: .value("Point", point.id), y: .value("Close", point.close))
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

            // Interactive scrubber: rule + lollipop + dot on the selected point.
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
                            Text(sel.date, format: spanSeconds < 2 * 86_400
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

                PointMark(x: .value("Point", sel.id), y: .value("Close", sel.close))
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
        .chartPlotStyle { $0.padding(.horizontal, 12) }
    }
}

/// The design tokens this chart needs, mirroring `Theme.kt` in :shared. Only the two
/// semantic colours: the surrounding card is drawn by Compose, so nothing else here
/// would ever be seen.
private enum Palette {
    static let positive = Color(red: 0.0, green: 0.541, blue: 0.239)   // #008a3d
    static let negative = Color(red: 0.729, green: 0.102, blue: 0.102) // #ba1a1a
}

// MARK: - Bridge

/// A hosting controller that keeps a handle on its model, so `update` can mutate the
/// chart in place instead of rebuilding it.
private final class PriceChartController: UIHostingController<PriceChartView> {
    let model = PriceChartModel()

    init() {
        super.init(rootView: PriceChartView(model: model))
        // The Compose card behind it draws the background; an opaque one would punch a
        // white rectangle through the card's rounded corners.
        view.backgroundColor = .clear
    }

    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used — this controller is only made in code")
    }
}

/// Implements the shared framework's `NativePriceChartRenderer`. Registered once, in
/// `iOSApp.init`; if it never were, the shared code falls back to its own chart rather
/// than failing.
final class PriceChartRenderer: NSObject, NativePriceChartRenderer {
    func makeController() -> UIViewController { PriceChartController() }

    func update(controller: UIViewController, points: [PricePoint], positive: Bool) {
        guard let controller = controller as? PriceChartController else { return }
        // Kotlin publishes epoch SECONDS; Foundation wants seconds since 1970 as well,
        // so this is a straight widening, not a unit conversion.
        controller.model.points = points.enumerated().map { index, point in
            ChartPoint(
                id: index,
                date: Date(timeIntervalSince1970: TimeInterval(point.epochSeconds)),
                close: point.close
            )
        }
        controller.model.positive = positive
    }
}
