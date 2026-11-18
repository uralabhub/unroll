import SwiftUI

/// Unroll's motion language: lively but disciplined.
/// - Every touch answers: presses squeeze, selections pop, counters roll.
/// - Content arrives with a short staggered rise; state changes slide and morph instead of cutting.
/// - Loops (pulse, sheen, skeleton) run only while something is actually live or loading,
///   never in grid cells, never on an idle screen.
/// - Springs settle in well under half a second, so nothing ever waits on an animation.
/// Every effect respects Reduce Motion and falls back to a plain cross-fade (or nothing).
enum Motion {
    /// Press feedback: a quick squeeze that springs back.
    static let press = Animation.spring(response: 0.24, dampingFraction: 0.62)
    /// Content arriving (lists, cards, sections).
    static let arrive = Animation.spring(response: 0.5, dampingFraction: 0.8)
    /// State changes inside a control (tabs, toggles, counters, selection).
    static let snap = Animation.spring(response: 0.32, dampingFraction: 0.76)
    /// Larger layout moves (screens, bars, panels).
    static let glide = Animation.spring(response: 0.42, dampingFraction: 0.86)
    /// A small celebratory overshoot (checkmarks, badges, selected chips).
    static let pop = Animation.spring(response: 0.34, dampingFraction: 0.5)
    /// Progress fills: steady, so it reads as work rather than a bounce.
    static let progress = Animation.easeOut(duration: 0.25)

    /// Stagger step for short lists; capped so long lists never wait.
    static func stagger(_ index: Int, step: Double = 0.045, cap: Int = 8) -> Double {
        Double(min(max(index, 0), cap)) * step
    }
}

// MARK: - Depth (flat design: kept as a no-op so older call sites compile)

extension View {
    func depth(_ level: CGFloat = 1) -> some View { self }
    func tilt(_ maxDegrees: Double = 4) -> some View { self }
    func scrollDepth() -> some View { self }
}

// MARK: - Press

/// Rows, tiles and icon buttons: squeeze and dim on press, spring back on release.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.94
    var lifted = false

    func makeBody(configuration: Configuration) -> some View {
        PressBody(configuration: configuration, scale: scale)
    }

    private struct PressBody: View {
        let configuration: ButtonStyle.Configuration
        let scale: CGFloat
        @Environment(\.accessibilityReduceMotion) private var reduce
        var body: some View {
            configuration.label
                .foregroundStyle(Mono.ink)
                .opacity(configuration.isPressed ? 0.6 : 1)
                .scaleEffect(configuration.isPressed && !reduce ? scale : 1)
                .animation(Motion.press, value: configuration.isPressed)
        }
    }
}

extension View {
    /// The press squeeze for custom button styles: scale + dim, springing back.
    func pressEffect(_ pressed: Bool, scale: CGFloat = 0.97, dim: Double = 0.8) -> some View {
        modifier(PressEffect(pressed: pressed, scale: scale, dim: dim))
    }
}

private struct PressEffect: ViewModifier {
    let pressed: Bool
    let scale: CGFloat
    let dim: Double
    @Environment(\.accessibilityReduceMotion) private var reduce
    func body(content: Content) -> some View {
        content
            .scaleEffect(pressed && !reduce ? scale : 1)
            .opacity(pressed ? dim : 1)
            .animation(Motion.press, value: pressed)
    }
}

// MARK: - Arrival

/// Fades, slides up and settles the first time it appears, staggered by index.
struct Rise: ViewModifier {
    var delay: Double = 0
    var distance: CGFloat = 18
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduce ? 0 : distance)
            .scaleEffect(shown || reduce ? 1 : 0.97, anchor: .top)
            .onAppear {
                guard !shown else { return }
                withAnimation((reduce ? Animation.easeOut(duration: 0.2) : Motion.arrive).delay(reduce ? 0 : delay)) { shown = true }
            }
    }
}

extension View {
    func rise(_ index: Int = 0, step: Double = 0.045, distance: CGFloat = 18) -> some View {
        modifier(Rise(delay: Motion.stagger(index, step: step), distance: distance))
    }

    /// Rolling digits for counters.
    func rollingNumber(_ value: Int) -> some View {
        contentTransition(.numericText(value: Double(value)))
            .animation(Motion.snap, value: value)
    }

    /// Rolling text for labels that contain numbers ("Importing 3 of 12").
    func rollingText(_ value: some Equatable) -> some View {
        contentTransition(.numericText())
            .animation(Motion.snap, value: value)
    }
}

// MARK: - Pop

extension View {
    /// A one-shot bounce (1 → 1.18 → 1) every time `trigger` changes.
    func pop(on trigger: some Equatable, scale: CGFloat = 1.18) -> some View {
        modifier(Pop(trigger: trigger, peak: scale))
    }
}

private struct Pop<T: Equatable>: ViewModifier {
    let trigger: T
    let peak: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduce
    func body(content: Content) -> some View {
        if reduce {
            content
        } else {
            content.keyframeAnimator(initialValue: CGFloat(1), trigger: trigger) { view, s in
                view.scaleEffect(s)
            } keyframes: { _ in
                SpringKeyframe(peak, duration: 0.12, spring: .snappy)
                SpringKeyframe(1, duration: 0.35, spring: .bouncy)
            }
        }
    }
}

/// Inserted views pop in from small, removed ones shrink away.
extension AnyTransition {
    static var popIn: AnyTransition {
        .asymmetric(insertion: .scale(scale: 0.4).combined(with: .opacity),
                    removal: .scale(scale: 0.8).combined(with: .opacity))
    }
    /// Bars and banners that come up from the bottom edge.
    static var riseFromBottom: AnyTransition {
        .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.96, anchor: .bottom)),
                    removal: .move(edge: .bottom).combined(with: .opacity))
    }
    /// One state of a bar handing over to the next: the new one rises, the old one sinks.
    static var swapUp: AnyTransition {
        .asymmetric(insertion: .offset(y: 14).combined(with: .opacity),
                    removal: .offset(y: -10).combined(with: .opacity))
    }
}

// MARK: - Live loops (only while something is live)

extension View {
    /// A ring that ripples out from a status dot while `active`.
    func pulseRing(_ active: Bool, color: Color = Mono.accent) -> some View {
        modifier(PulseRing(active: active, color: color))
    }

    /// A soft highlight that sweeps across while `active` (progress bars, skeletons).
    func sheen(_ active: Bool, opacity: Double = 0.35) -> some View {
        modifier(Sheen(active: active, strength: opacity))
    }

    /// A slow breathe for "waiting" glyphs while `active`.
    func breathe(_ active: Bool) -> some View {
        modifier(Breathe(active: active))
    }
}

/// One ring ripples out, then a rest with nothing redrawing, so an idle screen stays idle.
private struct PulseRing: ViewModifier {
    let active: Bool
    let color: Color
    @State private var p: CGFloat = 1
    @Environment(\.accessibilityReduceMotion) private var reduce
    func body(content: Content) -> some View {
        content.background {
            if active && !reduce {
                Circle().stroke(color, lineWidth: 1.5)
                    .scaleEffect(1 + p * 2.6)
                    .opacity((1 - p) * 0.7)
                    .allowsHitTesting(false)
                    .task {
                        while !Task.isCancelled {
                            var t = Transaction(); t.disablesAnimations = true
                            withTransaction(t) { p = 0 }
                            withAnimation(.easeOut(duration: 1.4)) { p = 1 }
                            try? await Task.sleep(for: .seconds(3.6))
                        }
                    }
            }
        }
    }
}

private struct Sheen: ViewModifier {
    let active: Bool
    let strength: Double
    @Environment(\.accessibilityReduceMotion) private var reduce
    func body(content: Content) -> some View {
        content.overlay {
            if active && !reduce {
                GeometryReader { geo in
                    TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
                        let w = max(geo.size.width, 1)
                        let band = max(60, w * 0.35)
                        let p = (ctx.date.timeIntervalSinceReferenceDate / 1.4).truncatingRemainder(dividingBy: 1)
                        LinearGradient(colors: [.white.opacity(0), .white.opacity(strength), .white.opacity(0)],
                                       startPoint: .leading, endPoint: .trailing)
                            .frame(width: band)
                            .offset(x: -band + p * (w + band))
                    }
                }
                .allowsHitTesting(false)
                .mask(content)
                .transition(.opacity)
            }
        }
    }
}

/// Dims and grows a touch, comes back, then rests (nothing redraws during the rest).
private struct Breathe: ViewModifier {
    let active: Bool
    @State private var low = false
    @Environment(\.accessibilityReduceMotion) private var reduce
    func body(content: Content) -> some View {
        content
            .scaleEffect(low ? 1.06 : 1)
            .opacity(low ? 0.6 : 1)
            .task(id: active && !reduce) {
                guard active && !reduce else { low = false; return }
                while !Task.isCancelled {
                    withAnimation(.easeInOut(duration: 0.9)) { low = true }
                    try? await Task.sleep(for: .seconds(0.9))
                    withAnimation(.easeInOut(duration: 0.9)) { low = false }
                    try? await Task.sleep(for: .seconds(2.3))
                }
            }
    }
}

// MARK: - Skeleton

/// Placeholder tiles that shimmer while the card is being read.
struct SkeletonGrid: View {
    var columns = 4
    var rows = 5
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RoundedRectangle(cornerRadius: 4).fill(Mono.tile).frame(width: 110, height: 14)
                .padding(.horizontal, Mono.gutter).padding(.top, 18).padding(.bottom, 10)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: columns), spacing: 2) {
                ForEach(0..<(columns * rows), id: \.self) { i in
                    Mono.tile.aspectRatio(1, contentMode: .fit)
                        .rise(i / columns + i % columns, step: 0.03, distance: 8)
                }
            }
        }
        .sheen(true, opacity: 0.08)
        .accessibilityHidden(true)
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
