import SwiftUI

/// Unroll's visual language: black, white and warm greys. Depth comes from layered surfaces and
/// soft shadows, not colour; motion is physical (springs), never decorative. Type is Nunito
/// throughout; data uses its tabular figures so numbers line up like a camera's top-plate display.
enum Mono {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        func c(_ v: UInt32) -> UIColor { UIColor(red: CGFloat(v >> 16 & 0xFF) / 255, green: CGFloat(v >> 8 & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1) }
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? c(dark) : c(light) })
    }
    static let bg = dyn(0xF4F4F2, 0x0A0A0B)
    /// Cards and sheets sit one step above the background.
    static let surface = dyn(0xFFFFFF, 0x151517)
    static let raised = dyn(0xEBEBE8, 0x212124)
    static let ink = dyn(0x0E0E0F, 0xF4F4F5)
    static let dim = dyn(0x6C6C70, 0x9A9AA0)
    static let faint = dyn(0xB5B5B8, 0x55555B)
    static let line = dyn(0xE3E3E0, 0x26262A)
    static let tile = surface
    /// Near-black feature surface, same in both modes.
    static let hero = dyn(0x111112, 0x1B1B1E)
    static let shadow = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? .clear : UIColor(white: 0, alpha: 0.10) })

    static let gutter: CGFloat = 20
    static let radius: CGFloat = 16
    static let cardRadius: CGFloat = 24

    /// One physical feel for every touch: quick, slightly springy, never bouncy.
    static let press = Animation.spring(response: 0.32, dampingFraction: 0.62)
    static let settle = Animation.spring(response: 0.42, dampingFraction: 0.88)

    /// Small uppercase label for section headers and data captions.
    static func caps(_ size: CGFloat = 11) -> Font { .custom(Nunito.name(Font.Weight.heavy), fixedSize: size) }
    /// Numbers and EXIF: Nunito with tabular figures.
    static func data(_ style: Font.TextStyle = .body, weight: Font.Weight = .semibold) -> Font {
        Font.sans(style, weight).monospacedDigit()
    }
}

/// Nunito (bundled, UIAppFonts). Sizes follow the iOS text styles and scale with Dynamic Type.
enum Nunito {
    static func name(_ w: Font.Weight) -> String {
        if w == .heavy || w == .black { return "Nunito-ExtraBold" }
        if w == .bold { return "Nunito-Bold" }
        if w == .semibold { return "Nunito-SemiBold" }
        if w == .medium { return "Nunito-Medium" }
        return "Nunito-Regular"
    }
    static func name(_ w: UIFont.Weight) -> String {
        if w >= .heavy { return "Nunito-ExtraBold" }
        if w >= .bold { return "Nunito-Bold" }
        if w >= .semibold { return "Nunito-SemiBold" }
        if w >= .medium { return "Nunito-Medium" }
        return "Nunito-Regular"
    }
    static func size(_ s: Font.TextStyle) -> CGFloat {
        switch s {
        case .largeTitle: return 34
        case .title: return 28
        case .title2: return 22
        case .title3: return 20
        case .headline, .body: return 17
        case .callout: return 16
        case .subheadline: return 15
        case .footnote: return 13
        case .caption: return 12
        case .caption2: return 11
        @unknown default: return 17
        }
    }
    static func uiFont(_ size: CGFloat, _ w: UIFont.Weight) -> UIFont {
        UIFont(name: name(w), size: size) ?? .systemFont(ofSize: size, weight: w)
    }
}

/// Old name kept for the renderers.
typealias Montserrat = Nunito

extension Font {
    static func sans(_ style: Font.TextStyle, _ weight: Font.Weight = .regular) -> Font {
        .custom(Nunito.name(weight), size: Nunito.size(style), relativeTo: style)
    }
    static func sans(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom(Nunito.name(weight), fixedSize: size)
    }
}

/// Phosphor icon (MIT) from the asset catalog, by its SF Symbol role name.
struct Ph: View {
    let asset: String
    var size: CGFloat
    init(_ sf: String, size: CGFloat = 18) { asset = Ph.map[sf] ?? sf; self.size = size }
    var body: some View {
        Image(asset).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size)
    }
    static let map: [String: String] = [
        "lock.fill": "ph-lock-simple-fill", "square.and.arrow.up": "ph-export", "star.fill": "ph-star-fill", "star": "ph-star",
        "xmark": "ph-x", "video": "ph-video-camera", "gearshape": "ph-gear-six", "exclamationmark": "ph-warning-bold",
        "ellipsis": "ph-dots-three-bold", "clock": "ph-timer", "chevron.right": "ph-caret-right-bold", "chevron.left": "ph-arrow-left",
        "chevron.down": "ph-caret-down-bold", "checkmark": "ph-check-bold", "arrow.right": "ph-arrow-right-bold",
        "wifi": "ph-wifi-high", "sdcard": "ph-sim-card", "photo.artframe": "ph-frame-corners", "map": "ph-path",
        "camera.filters": "ph-aperture", "checkmark.circle.fill": "ph-check-circle-fill", "play.fill": "ph-play-fill",
        "stop.fill": "ph-stop-fill", "usb": "ph-usb", "film": "ph-film-strip", "crown": "ph-crown-simple", "queued": "ph-clock",
    ]
}

extension View {
    /// A card one step above the background: white on a soft, wide shadow in light mode; a lifted grey with a hairline in dark.
    func card(radius: CGFloat = Mono.cardRadius, shadow: CGFloat = 14) -> some View {
        self
            .background(Mono.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Mono.line.opacity(0.6), lineWidth: 0.5))
            .shadow(color: Mono.shadow, radius: shadow, y: shadow / 3)
    }

    /// Rises 16pt and fades in, [index] × 45 ms after it appears.
    func riseIn(_ index: Int) -> some View { modifier(RiseIn(index: index)) }
}

private struct RiseIn: ViewModifier {
    let index: Int
    @State private var shown = false
    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 16)
            .onAppear { withAnimation(.spring(response: 0.55, dampingFraction: 0.85).delay(Double(index) * 0.045)) { shown = true } }
    }
}

/// Kept so older call sites compile; everything resolves to the mono palette.
enum Theme {
    static let accent = Mono.ink
    static let card = Mono.raised
    static let star = Mono.ink
    static let lock = Mono.ink
}

// MARK: - Text

struct CapsLabel: View {
    let text: String
    var color: Color = Mono.dim
    init(_ text: String, color: Color = Mono.dim) { self.text = text; self.color = color }
    var body: some View {
        Text(text.uppercased()).font(Mono.caps()).tracking(1.1).foregroundStyle(color)
    }
}

struct Hairline: View {
    var body: some View { Rectangle().fill(Mono.line).frame(height: 1 / UIScreen.main.scale) }
}

// MARK: - Sections and rows

/// A titled block of rows separated by hairlines. No card, no fill.
struct MonoSection<Content: View>: View {
    let title: String?
    var trailing: String? = nil
    @ViewBuilder var content: Content

    init(_ title: String? = nil, trailing: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.trailing = trailing
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                HStack {
                    CapsLabel(title)
                    Spacer()
                    if let trailing { CapsLabel(trailing) }
                }
                .padding(.horizontal, Mono.gutter + 2)
                .padding(.bottom, 10)
            }
            _VariadicView.Tree(DividedLayout()) { content }
                .card(radius: 22, shadow: 10)
                .padding(.horizontal, 16)
        }
    }
}

private struct DividedLayout: _VariadicView_MultiViewRoot {
    func body(children: _VariadicView.Children) -> some View {
        VStack(spacing: 0) {
            ForEach(children) { child in
                child
                if child.id != children.last?.id { Hairline().padding(.leading, Mono.gutter) }
            }
        }
    }
}

/// Standard list row: title, optional detail underneath, optional value on the right, chevron.
struct MonoRow<Trailing: View>: View {
    let title: String
    var detail: String? = nil
    var value: String? = nil
    var chevron = true
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.sans(.body, .semibold)).foregroundStyle(Mono.ink)
                if let detail {
                    Text(detail).font(.sans(.footnote)).foregroundStyle(Mono.dim).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if let value { Text(value).font(Mono.data(.subheadline)).foregroundStyle(Mono.dim) }
            trailing
            if chevron {
                Ph("chevron.right", size: 13).foregroundStyle(Mono.faint)
            }
        }
        .padding(.horizontal, Mono.gutter)
        .padding(.vertical, 14)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }
}

extension MonoRow where Trailing == EmptyView {
    init(_ title: String, detail: String? = nil, value: String? = nil, chevron: Bool = true) {
        self.init(title: title, detail: detail, value: value, chevron: chevron) { EmptyView() }
    }
}

/// Key / value line for EXIF and recipe data.
struct DataRow: View {
    let key: String
    let value: String
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(key).font(.sans(.footnote, .semibold)).foregroundStyle(Mono.dim)
            Spacer(minLength: 12)
            Text(value).font(Mono.data(.subheadline, weight: .bold)).foregroundStyle(Mono.ink).multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 7)
    }
}

// MARK: - Buttons

/// Solid ink button (black on light, white on dark). Sinks on a spring when pressed.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.sans(.body, .heavy))
            .frame(maxWidth: .infinity, minHeight: 54)
            .foregroundStyle(enabled ? Mono.bg : Mono.ink.opacity(0.4))
            .background(Mono.ink.opacity(enabled ? 1 : 0.1), in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .shadow(color: enabled ? Mono.shadow : .clear, radius: 10, y: 4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(Mono.press, value: configuration.isPressed)
            .animation(Mono.settle, value: enabled)
    }
}

/// Quiet button on a raised surface, same footprint as primary.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.sans(.body, .bold))
            .frame(maxWidth: .infinity, minHeight: 54)
            .foregroundStyle(Mono.ink.opacity(enabled ? 1 : 0.3))
            .background(Mono.raised, in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(Mono.press, value: configuration.isPressed)
    }
}

/// Small capsule button used inline (Cancel, Open Photos…).
struct OutlineChipStyle: ButtonStyle {
    var filled = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.sans(.footnote, .heavy))
            .padding(.horizontal, 14).frame(minHeight: 34)
            .foregroundStyle(filled ? Mono.bg : Mono.ink)
            .background(filled ? Mono.ink : Mono.raised, in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(Mono.press, value: configuration.isPressed)
    }
}

/// Segmented filter strip: an ink pill slides on a spring to the selected item and the label inverts under it.
struct TabStrip<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var count: ((T) -> Int?)? = nil
    var scrolls = true
    @Namespace private var pill

    var body: some View {
        Group {
            if scrolls {
                ScrollView(.horizontal) { row.padding(.horizontal, 16) }
                    .scrollIndicators(.hidden)
            } else {
                row.padding(.horizontal, 16)
            }
        }
    }

    private var row: some View {
        HStack(spacing: 2) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button {
                    withAnimation(Mono.press) { selection = item }
                    Haptics.tap()
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Text(label(item)).font(.sans(.subheadline, .heavy))
                        if let n = count?(item) {
                            Text("\(n)").font(Mono.data(.caption, weight: .bold)).opacity(0.7)
                                .contentTransition(.numericText(value: Double(n)))
                        }
                    }
                    .foregroundStyle(on ? Mono.bg : Mono.dim)
                    .padding(.horizontal, 14).frame(height: 38)
                    .background {
                        if on { Capsule().fill(Mono.ink).matchedGeometryEffect(id: "pill", in: pill) }
                    }
                    .fixedSize()
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
    }
}

/// On/off chip for option toggles; fills with ink when on.
struct ChipToggle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { withAnimation(Mono.settle) { configuration.isOn.toggle() }; Haptics.tap() } label: {
            configuration.label
                .font(.sans(.subheadline, .bold))
                .lineLimit(1)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .foregroundStyle(configuration.isOn ? Mono.bg : Mono.dim)
                .background(configuration.isOn ? Mono.ink : Mono.raised, in: Capsule())
        }
        .buttonStyle(PressStyle(scale: 0.94))
    }
}

/// Spring sink, no colour change — for tiles, cards and chips.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.965
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(Mono.press, value: configuration.isPressed)
    }
}

/// Five stars. Filled = ink, empty = faint; a change pops on a spring.
struct StarRow: View {
    let rating: Int
    var size: CGFloat = 12
    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { n in
                Ph(n <= rating ? "star.fill" : "star", size: size)
                    .foregroundStyle(n <= rating ? Mono.ink : Mono.faint)
                    .scaleEffect(n <= rating ? 1 : 0.85)
                    .animation(Mono.press, value: rating)
            }
        }
    }
}

/// Empty / status message: plain type, no illustration.
struct QuietMessage: View {
    let title: String
    var message: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.sans(.title3, .heavy))
            if let message { Text(message).font(.sans(.subheadline)).foregroundStyle(Mono.dim) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Mono.gutter)
        .padding(.vertical, 28)
    }
}

// MARK: - Zoom navigation (iOS 18: a photo grows out of its grid cell and shrinks back into it)

extension View {
    @ViewBuilder func zoomSource(id: String, in ns: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) { self.matchedTransitionSource(id: id, in: ns) } else { self }
    }
    @ViewBuilder func zoomTransition(sourceID: String, in ns: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) { self.navigationTransition(.zoom(sourceID: sourceID, in: ns)) } else { self }
    }
}
