import SwiftUI
import CoreMotion

/// Unroll's motion language. Everything moves on springs (no linear fades, no bounce for its own
/// sake), surfaces sit on soft layered shadows, and nothing glows. Every effect checks
/// Reduce Motion and falls back to a plain cross-fade.
enum Motion {
    /// Press-down / release of anything tappable.
    static let press = Animation.spring(response: 0.26, dampingFraction: 0.62)
    /// Content arriving (lists, cards, sheets of content).
    static let arrive = Animation.spring(response: 0.55, dampingFraction: 0.82)
    /// State changes inside a control (tabs, toggles, counters).
    static let snap = Animation.spring(response: 0.34, dampingFraction: 0.8)
    /// Larger layout moves (screens, HUD).
    static let glide = Animation.spring(response: 0.48, dampingFraction: 0.86)

    /// Stagger step for lists; capped so long lists don't keep the user waiting.
    static func stagger(_ index: Int, step: Double = 0.045, cap: Int = 10) -> Double {
        Double(min(index, cap)) * step
    }
}

// MARK: - Depth

/// Two stacked shadows — a tight contact shadow and a wide ambient one — so cards read as
/// sitting on the page instead of being printed on it. Lighter in dark mode, where shadows vanish.
struct Depth: ViewModifier {
    var level: CGFloat = 1
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let k = scheme == .dark ? 0.55 : 1.0
        content
            .shadow(color: .black.opacity(0.05 * k * level), radius: 1, y: 1)
            .shadow(color: .black.opacity(0.08 * k * level), radius: 18 * level, y: 10 * level)
    }
}

extension View {
    func depth(_ level: CGFloat = 1) -> some View { modifier(Depth(level: level)) }
}

// MARK: - Press

/// Tiles, cards, round buttons: sink on press with a spring, shadow tightens as it goes down.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    var lifted = false

    func makeBody(configuration: Configuration) -> some View {
        PressBody(configuration: configuration, scale: scale, lifted: lifted)
    }

    private struct PressBody: View {
        let configuration: ButtonStyleConfiguration
        let scale: CGFloat
        let lifted: Bool
        @Environment(\.accessibilityReduceMotion) private var reduce

        var body: some View {
            let down = configuration.isPressed
            configuration.label
                .foregroundStyle(Mono.ink)
                .modifier(Depth(level: lifted ? (down ? 0.35 : 1) : 0))
                .scaleEffect(down && !reduce ? scale : 1)
                .opacity(down && reduce ? 0.7 : 1)
                .animation(Motion.press, value: down)
                .sensoryFeedback(.impact(weight: .light, intensity: 0.6), trigger: down) { _, new in new }
        }
    }
}

// MARK: - Arrival

/// Fades and rises into place the first time it appears, after `delay`.
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
                withAnimation(reduce ? .easeOut(duration: 0.2) : Motion.arrive.delay(delay)) { shown = true }
            }
    }
}

extension View {
    func rise(_ index: Int = 0, step: Double = 0.05, distance: CGFloat = 18) -> some View {
        modifier(Rise(delay: Motion.stagger(index, step: step), distance: distance))
    }

    /// Cards shrink and dim slightly as they scroll off the top or bottom edge.
    func scrollDepth() -> some View {
        scrollTransition(.interactive, axis: .vertical) { v, phase in
            v.scaleEffect(phase.isIdentity ? 1 : 0.96)
             .opacity(phase.isIdentity ? 1 : 0.85)
        }
    }

    /// A photo "developing": arrives desaturated and soft, then resolves.
    func developing(_ done: Bool) -> some View {
        self.saturation(done ? 1 : 0)
            .blur(radius: done ? 0 : 8)
            .opacity(done ? 1 : 0)
            .scaleEffect(done ? 1 : 1.06)
    }
}

/// Transition form of `developing(_:)`.
struct Developing: ViewModifier {
    let done: Bool
    func body(content: Content) -> some View { content.developing(done) }
}

// MARK: - Tilt

/// Device attitude at 30 Hz while something on screen is listening.
@MainActor
@Observable
final class DeviceTilt {
    static let shared = DeviceTilt()
    private(set) var pitch: Double = 0
    private(set) var roll: Double = 0
    @ObservationIgnored private let manager = CMMotionManager()
    @ObservationIgnored private var listeners = 0
    @ObservationIgnored private var reference: (Double, Double)?

    func start() {
        listeners += 1
        guard listeners == 1, manager.isDeviceMotionAvailable else { return }
        reference = nil
        manager.deviceMotionUpdateInterval = 1 / 30
        manager.startDeviceMotionUpdates(to: .main) { [weak self] m, _ in
            guard let self, let a = m?.attitude else { return }
            // Relative to how the phone was held when the view appeared.
            if self.reference == nil { self.reference = (a.pitch, a.roll) }
            let (p0, r0) = self.reference!
            // Light low-pass so the card floats rather than jitters.
            self.pitch += ((a.pitch - p0) - self.pitch) * 0.18
            self.roll += ((a.roll - r0) - self.roll) * 0.18
        }
    }

    func stop() {
        listeners = max(0, listeners - 1)
        guard listeners == 0 else { return }
        manager.stopDeviceMotionUpdates()
        pitch = 0; roll = 0
    }
}

/// Tilts the view a few degrees with the phone, like holding a print up to the light.
struct Tilt: ViewModifier {
    var maxDegrees: Double = 4
    @State private var tilt = DeviceTilt.shared
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        let clamp = { (v: Double) in max(-1, min(1, v / 0.5)) }
        let x = reduce ? 0 : clamp(tilt.pitch) * maxDegrees
        let y = reduce ? 0 : clamp(tilt.roll) * maxDegrees
        content
            .rotation3DEffect(.degrees(-x), axis: (1, 0, 0), perspective: 0.6)
            .rotation3DEffect(.degrees(y), axis: (0, 1, 0), perspective: 0.6)
            .onAppear { if !reduce { tilt.start() } }
            .onDisappear { if !reduce { tilt.stop() } }
    }
}

extension View {
    func tilt(_ maxDegrees: Double = 4) -> some View { modifier(Tilt(maxDegrees: maxDegrees)) }

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
