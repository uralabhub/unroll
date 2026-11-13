import SwiftUI

/// Unroll's visual language: black, white and greys only. Type and hairlines do the work —
/// no gradients, glows, tinted icon bubbles or decorative colour. Data (EXIF, counts, times)
/// is set in monospaced digits, like a camera's top-plate display.
enum Mono {
    static let bg = Color(uiColor: .systemBackground)
    static let raised = Color(uiColor: .secondarySystemBackground)
    static let ink = Color.primary
    static let dim = Color.secondary
    static let faint = Color(uiColor: .tertiaryLabel)
    static let line = Color(uiColor: .separator)
    /// Neutral grey surface for tiles (systemGray6 has a blue cast; this does not).
    static let tile = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.1, alpha: 1) : UIColor(white: 0.955, alpha: 1) })
    /// Near-black feature surface, same in both modes.
    static let hero = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.14, alpha: 1) : UIColor(white: 0.07, alpha: 1) })

    static let gutter: CGFloat = 20
    static let radius: CGFloat = 10

    /// Small uppercase monospaced label used for section headers and data captions.
    static func caps(_ size: CGFloat = 11) -> Font { .system(size: size, weight: .medium, design: .monospaced) }
    static func data(_ style: Font.TextStyle = .body, weight: Font.Weight = .regular) -> Font {
        .system(style, design: .monospaced).weight(weight)
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
        Text(text.uppercased()).font(Mono.caps()).tracking(1.2).foregroundStyle(color)
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
                Text(title).font(.body).foregroundStyle(Mono.ink)
                if let detail {
                    Text(detail).font(.footnote).foregroundStyle(Mono.dim).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if let value { Text(value).font(Mono.data(.subheadline)).foregroundStyle(Mono.dim) }
            trailing
            if chevron {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Mono.faint)
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
            Text(key.uppercased()).font(Mono.caps(10)).tracking(1).foregroundStyle(Mono.dim)
            Spacer(minLength: 12)
            Text(value).font(Mono.data(.footnote, weight: .medium)).foregroundStyle(Mono.ink).multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 7)
    }
}

// MARK: - Buttons

/// Solid ink button (black on light, white on dark).
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(enabled ? Mono.bg : Mono.ink.opacity(0.45))
            .background(Mono.ink.opacity(enabled ? (configuration.isPressed ? 0.75 : 1) : 0.1),
                        in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
    }
}

/// Outlined button, same footprint as primary.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium))
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(Mono.ink.opacity(enabled ? 1 : 0.3))
            .background(Mono.ink.opacity(configuration.isPressed ? 0.08 : 0),
                        in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Mono.radius, style: .continuous)
                .strokeBorder(Mono.ink.opacity(enabled ? 0.35 : 0.12), lineWidth: 1))
    }
}

/// Small text button used inline (Start / Stop, Select day…).
struct OutlineChipStyle: ButtonStyle {
    var filled = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote.weight(.semibold))
            .padding(.horizontal, 14).frame(minHeight: 32)
            .foregroundStyle(filled ? Mono.bg : Mono.ink)
            .background(filled ? Mono.ink : Color.clear, in: Capsule())
            .overlay(Capsule().strokeBorder(Mono.ink.opacity(filled ? 0 : 0.35), lineWidth: 1))
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Text tabs with an underline for the selected one. Used for filters and pickers.
struct TabStrip<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var count: ((T) -> Int?)? = nil
    var scrolls = true

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
        HStack(spacing: 22) {
            ForEach(items, id: \.self) { item in
                let on = item == selection
                Button {
                    withAnimation(.snappy(duration: 0.2)) { selection = item }
                    Haptics.tap()
                } label: {
                    VStack(spacing: 7) {
                        HStack(alignment: .firstTextBaseline, spacing: 5) {
                            Text(label(item)).font(.subheadline.weight(on ? .semibold : .regular))
                            if let n = count?(item) {
                                Text("\(n)").font(Mono.data(.caption2))
                            }
                        }
                        .foregroundStyle(on ? Mono.ink : Mono.dim)
                        Rectangle().fill(on ? Mono.ink : .clear).frame(height: 1.5)
                    }
                    .fixedSize()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// On/off chip for option toggles.
struct ChipToggle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle(); Haptics.tap() } label: {
            configuration.label
                .font(.footnote.weight(.medium))
                .lineLimit(1)
                .padding(.horizontal, 12).frame(minHeight: 32)
                .foregroundStyle(configuration.isOn ? Mono.bg : Mono.dim)
                .background(configuration.isOn ? Mono.ink : .clear, in: Capsule())
                .overlay(Capsule().strokeBorder(Mono.ink.opacity(configuration.isOn ? 0 : 0.3), lineWidth: 1))
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
                Image(systemName: n <= rating ? "star.fill" : "star")
                    .font(.system(size: size, weight: .medium))
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
            Text(title).font(.headline)
            if let message { Text(message).font(.subheadline).foregroundStyle(Mono.dim) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Mono.gutter)
        .padding(.vertical, 28)
    }
}
