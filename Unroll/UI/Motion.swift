import SwiftUI

/// Unroll's motion language: quick, critically damped, purposeful.
/// - Nothing animates while the screen is idle.
/// - Nothing overshoots (no bounce): springs are fully damped.
/// - Motion explains a change (where a photo went, what is importing); it never decorates.
/// Every effect respects Reduce Motion and falls back to a plain cross-fade.
enum Motion {
    /// Press feedback: a fast dim, no scale.
    static let press = Animation.easeOut(duration: 0.12)
    /// Content arriving (lists, cards).
    static let arrive = Animation.smooth(duration: 0.3)
    /// State changes inside a control (tabs, toggles, counters, selection).
    static let snap = Animation.smooth(duration: 0.22)
    /// Larger layout moves (screens, bars, panels).
    static let glide = Animation.smooth(duration: 0.35)
    /// Progress fills: linear-ish so it reads as steady work, not a spring.
    static let progress = Animation.easeOut(duration: 0.25)

    /// Stagger step for short lists; capped so long lists never wait.
    static func stagger(_ index: Int, step: Double = 0.03, cap: Int = 6) -> Double {
        Double(min(index, cap)) * step
    }
}

// MARK: - Depth (flat design: kept as a no-op so older call sites compile)

extension View {
    func depth(_ level: CGFloat = 1) -> some View { self }
    func tilt(_ maxDegrees: Double = 4) -> some View { self }
    func scrollDepth() -> some View { self }
}

// MARK: - Press

/// Rows, tiles and icon buttons: dim on press. No scaling, no shadow.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 1
    var lifted = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Mono.ink)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

// MARK: - Arrival

/// Fades in the first time it appears. Used for a handful of blocks, never for grid cells.
struct Rise: ViewModifier {
    var delay: Double = 0
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .onAppear {
                guard !shown else { return }
                withAnimation(Motion.arrive.delay(delay)) { shown = true }
            }
    }
}

extension View {
    func rise(_ index: Int = 0, step: Double = 0.03, distance: CGFloat = 0) -> some View {
        modifier(Rise(delay: Motion.stagger(index, step: step)))
    }

    /// Rolling digits for counters.
    func rollingNumber(_ value: Int) -> some View {
        contentTransition(.numericText(value: Double(value)))
            .animation(Motion.snap, value: value)
    }
}

// MARK: - Zoom transition (iOS 18+, plain push before)

extension View {
    @ViewBuilder
    func zoomSource(id: some Hashable, in ns: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) { matchedTransitionSource(id: id, in: ns) } else { self }
    }

    @ViewBuilder
    func zoomDestination(id: some Hashable, in ns: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) { navigationTransition(.zoom(sourceID: id, in: ns)) } else { self }
    }
}
