import SwiftUI

/// Unroll's visual language: a dark, neutral photo tool (think Photos, Lightroom, Halide).
/// The photos carry the colour; the chrome is black, white and greys. System type (SF Pro) for
/// words, tabular digits for data. No shadows, no bounce, no decoration that moves on its own.
enum Mono {
    static let bg = Color(uiColor: .systemBackground)
    static let raised = Color(uiColor: .secondarySystemBackground)
    static let ink = Color.primary
    static let dim = Color.secondary
    static let faint = Color(uiColor: .tertiaryLabel)
    static let line = Color(uiColor: .separator)
    /// Neutral grey surface for controls and placeholders (no blue cast).
    static let tile = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.12, alpha: 1) : UIColor(white: 0.94, alpha: 1) })
    /// Page canvas.
    static let canvas = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.0, alpha: 1) : UIColor(white: 0.97, alpha: 1) })
    /// Grouped surface on the canvas (list sections).
    static let surface = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.09, alpha: 1) : UIColor(white: 1, alpha: 1) })
    /// Kept for older call sites: same as `surface`.
    static let hero = surface
    /// The one accent: used for progress and the live state only.
    static let accent = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 1.0, green: 0.78, blue: 0.27, alpha: 1) : UIColor(red: 0.85, green: 0.55, blue: 0.0, alpha: 1) })

    static let gutter: CGFloat = 16
    static let radius: CGFloat = 12

    // MARK: Type: SF Pro for words, tabular digits for data.

    /// System text style, scaling with Dynamic Type. `.headline` is semibold, as the system style is.
    static func sans(_ style: Font.TextStyle = .body, weight: Font.Weight? = nil) -> Font {
        let f = Font.system(style)
        return weight.map { f.weight($0) } ?? f
    }

    /// System font at a fixed size (display numbers in renderers and badges).
    static func sans(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }

    /// UIKit counterpart for renderers and navigation bars.
    static func uiSans(_ size: CGFloat, weight: UIFont.Weight = .regular) -> UIFont {
        .systemFont(ofSize: size, weight: weight)
    }

    /// Small section label. Sentence case at footnote size: quiet, not shouty.
    static func caps(_ size: CGFloat = 11) -> Font { .system(size: max(size, 12), weight: .semibold) }
    /// EXIF values, counts and times: tabular digits so numbers don't jitter.
    static func data(_ style: Font.TextStyle = .body, weight: Font.Weight = .regular) -> Font {
        sans(style, weight: weight).monospacedDigit()
    }
}

/// Kept so older call sites compile; everything resolves to the mono palette.
enum Theme {
    static let accent = Mono.ink
    static let card = Mono.raised
    static let star = Mono.ink
    static let lock = Mono.ink
}

// MARK: - Icons

/// Phosphor icon (MIT) from the asset catalog: vector, template-tinted by `foregroundStyle`.
/// Names match phosphoricons.com; filled variants end in `-fill`.
struct Icon: View {
    let name: String
    var size: CGFloat
    init(_ name: String, size: CGFloat = 20) { self.name = name; self.size = size }
    var body: some View {
        Image("ph.\(name)").resizable().renderingMode(.template).scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

// MARK: - Text

struct CapsLabel: View {
    let text: String
    var color: Color = Mono.dim
    init(_ text: String, color: Color = Mono.dim) { self.text = text; self.color = color }
    var body: some View {
        Text(text).font(Mono.sans(.footnote, weight: .semibold)).foregroundStyle(color)
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
                .padding(.horizontal, Mono.gutter)
                .padding(.bottom, 8)
            }
            Hairline()
            _VariadicView.Tree(DividedLayout()) { content }
        }
    }
}

private struct DividedLayout: _VariadicView_MultiViewRoot {
    func body(children: _VariadicView.Children) -> some View {
        VStack(spacing: 0) {
            ForEach(children) { child in
                child
                Hairline().padding(.leading, child.id == children.last?.id ? 0 : Mono.gutter)
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
                Text(title).font(Mono.sans(.body)).foregroundStyle(Mono.ink)
                if let detail {
                    Text(detail).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if let value { Text(value).font(Mono.data(.subheadline)).foregroundStyle(Mono.dim) }
            trailing
            if chevron {
                Icon("caret-right", size: 14).foregroundStyle(Mono.faint)
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
            Text(key).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
            Spacer(minLength: 12)
            Text(value).font(Mono.data(.footnote, weight: .medium)).foregroundStyle(Mono.ink).multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 7)
    }
}

// MARK: - Buttons

/// Solid ink button (white on dark, black on light). Press = quick dim, no bounce.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Mono.sans(.body, weight: .semibold))
            .lineLimit(1).minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 12)
            .foregroundStyle(enabled ? Mono.bg : Mono.ink.opacity(0.4))
            .background(Mono.ink.opacity(enabled ? 1 : 0.1),
                        in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .animation(Motion.press, value: configuration.isPressed)
            .animation(Motion.snap, value: enabled)
            .sensoryFeedback(.impact(weight: .light, intensity: 0.5), trigger: configuration.isPressed) { _, new in new }
    }
}

/// Grey filled button, same footprint as primary.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Mono.sans(.body, weight: .medium))
            .lineLimit(1).minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 12)
            .foregroundStyle(Mono.ink.opacity(enabled ? 1 : 0.3))
            .background(Mono.tile, in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

/// Small inline button (Start / Stop, Select day…).
struct OutlineChipStyle: ButtonStyle {
    var filled = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Mono.sans(.footnote, weight: .semibold))
            .padding(.horizontal, 12).frame(minHeight: 30)
            .foregroundStyle(filled ? Mono.bg : Mono.ink)
            .background(filled ? Mono.ink : Mono.tile, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

/// Segmented text tabs with a sliding pill under the selected one.
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
                ScrollView(.horizontal) { row.padding(.horizontal, Mono.gutter) }
                    .scrollIndicators(.hidden)
            } else {
                row.padding(.horizontal, Mono.gutter)
            }
        }
    }

    private var row: some View {
        HStack(spacing: 4) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button {
                    withAnimation(Motion.snap) { selection = item }
                    Haptics.select()
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Text(label(item)).font(Mono.sans(.subheadline, weight: .semibold))
                        if let n = count?(item) {
                            Text("\(n)").font(Mono.data(.caption)).foregroundStyle(on ? Mono.ink.opacity(0.6) : Mono.faint)
                        }
                    }
                    .foregroundStyle(on ? Mono.ink : Mono.dim)
                    .padding(.horizontal, 12).frame(minHeight: 32)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Mono.tile)
                                .matchedGeometryEffect(id: "pill", in: pill)
                        }
                    }
                    .fixedSize()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

/// On/off chip for option toggles.
struct ChipToggle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { withAnimation(Motion.snap) { configuration.isOn.toggle() }; Haptics.select() } label: {
            configuration.label
                .font(Mono.sans(.footnote, weight: .medium))
                .lineLimit(1)
                .padding(.horizontal, 12).frame(minHeight: 32)
                .foregroundStyle(configuration.isOn ? Mono.bg : Mono.ink)
                .background(configuration.isOn ? Mono.ink : Mono.tile, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// Five stars drawn as plain glyphs. Filled = ink, empty = faint.
struct StarRow: View {
    let rating: Int
    var size: CGFloat = 11
    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { n in
                Icon(n <= rating ? "star-fill" : "star", size: size + 1)
                    .foregroundStyle(n <= rating ? Mono.ink : Mono.faint)
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
            Text(title).font(Mono.sans(.headline))
            if let message { Text(message).font(Mono.sans(.subheadline)).foregroundStyle(Mono.dim) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Mono.gutter)
        .padding(.vertical, 28)
    }
}
