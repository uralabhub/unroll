import SwiftUI

/// One page of the first-run tour: a picture, what it's for, and how to do it.
struct TourPage: Identifiable {
    let id: Int
    let symbol: String
    let kicker: String
    let title: String
    let body: String
    let tips: [(symbol: String, text: String)]
}

extension TourPage {
    /// Every feature, in the order people meet them: get photos in, pick, import, then the extras.
    @MainActor static let all: [TourPage] = [
        TourPage(id: 0, symbol: "camera.aperture", kicker: "Welcome to Unroll",
                 title: "From camera to posted, in seconds",
                 body: "Unroll pulls photos straight off your camera, keeps the good ones, puts them on the map and frames them for sharing. Here’s the one‑minute tour.",
                 tips: [("lock.shield", "No account, no cloud. Everything stays on your phone."),
                        ("doc.badge.ellipsis", "Your original files are never modified."),
                        ("hand.draw", "Swipe to continue, or skip any time.")]),
        TourPage(id: 1, symbol: "cable.connector", kicker: "1 · Connect",
                 title: "Plug in your camera",
                 body: "Use a USB‑C cable. No pairing, no Bluetooth. The newest photos show up first while the rest load.",
                 tips: [("sdcard", "Got a card reader? Tap Open an SD card on Home."),
                        ("wifi", "Ricoh GR III / IIIx: Settings › Connections › Wi‑Fi."),
                        ("play.circle", "No camera handy? Try the demo roll on Home.")]),
        TourPage(id: 2, symbol: "square.grid.3x3.fill", kicker: "2 · Browse",
                 title: "Find the keepers fast",
                 body: "Stars and protect marks you set on the camera come across, so your picks are already sorted.",
                 tips: [("star.fill", "Tabs: All · Not imported · Picks (rated or protected on the camera)."),
                        ("line.3.horizontal.decrease.circle", "Filter button: by stars, protected, RAW or shot today."),
                        ("hand.tap", "Tap a photo to see it full size. Touch and hold for Open, Import, Select."),
                        ("ellipsis.circle", "The ⋯ menu has tile size, recap card and every tool.")]),
        TourPage(id: 3, symbol: "square.and.arrow.down.fill", kicker: "3 · Import",
                 title: "Into Photos in one tap",
                 body: "Import your picks or everything new from the bar at the bottom. Unroll remembers what’s already in Photos, so nothing comes in twice.",
                 tips: [("checkmark.circle", "Tap Select to choose by hand, or select a whole day at once."),
                        ("square.stack", "JPG + RAW pairs land as one photo, full resolution."),
                        ("cable.connector.slash", "Unplugged mid-import? It pauses and carries on when you plug back in."),
                        ("bolt", "Settings › When a camera connects: import automatically.")]),
        TourPage(id: 4, symbol: "camera.filters", kicker: "4 · Recipes",
                 title: "See how every shot was made",
                 body: "Open a photo and tap Info for exposure, lens and the film recipe: Fujifilm film simulations, Nikon Picture Control, Sony Creative Style.",
                 tips: [("book", "Recipes collect in your Recipe book as you import."),
                        ("square.and.arrow.up", "Share a recipe as a card, or copy it as text.")]),
        TourPage(id: 5, symbol: "figure.walk", kicker: "5 · Location",
                 title: "Put photos on the map",
                 body: "Your camera doesn’t need GPS or Bluetooth. Unroll adds the location when it imports.",
                 tips: [("play.fill", "Tap Start on Photo walk before you head out, Stop when you’re done."),
                        ("mappin.and.ellipse", "Photos taken along the way get the spot where you stood."),
                        ("iphone", "No walk? It borrows the location of phone photos taken within 20 minutes."),
                        ("map", "Past routes live in Walk history.")]),
        TourPage(id: 6, symbol: "clock.badge.checkmark", kicker: "6 · Clock sync",
                 title: "Fix the camera’s clock",
                 body: "A camera clock that drifts or sits in the wrong time zone puts photos in the wrong place. Fix it once per camera.",
                 tips: [("qrcode", "Open Clock sync and photograph the code on screen with your camera."),
                        ("cable.connector", "Connect the camera."),
                        ("checkmark.seal", "Tap Read from camera. Every import is corrected from then on.")]),
        TourPage(id: 7, symbol: "rectangle.portrait.on.rectangle.portrait.fill", kicker: "7 · Share",
                 title: "Frames and recap cards",
                 body: "Frame Studio adds a clean border with your settings or recipe: six styles, four ratios, full resolution.",
                 tips: [("square.dashed", "Tap Frame on any photo, or Frame a photo on Home for pictures already on your phone."),
                        ("rectangle.portrait", "Recap card: up to 6 photos and your walk’s route, 9:16 for stories. From ⋯ or Select › Recap."),
                        ("square.and.arrow.up", "Save to Photos or share straight from the editor.")]),
        TourPage(id: 8, symbol: "sparkles", kicker: "Free & Pro",
                 title: "Free to start",
                 body: "See everything on the card, with EXIF and recipes, and import \(Quota.freePerDay) photos a day for free.",
                 tips: [("infinity", "Pro: unlimited imports, auto-import of picks, every frame style, no watermark."),
                        ("questionmark.circle", "Replay this tour any time from the ? on Home or from Settings.")]),
    ]
}

/// The first-run tour. Shown once, and again from Home's ? or Settings.
struct TourView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0
    @State private var opening = false
    private let pages = TourPage.all

    private var isLast: Bool { page == pages.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("\(page + 1) / \(pages.count)")
                    .font(Mono.data(.footnote)).foregroundStyle(Mono.dim)
                    .contentTransition(.numericText())
                    .animation(Motion.snap, value: page)
                Spacer()
                if !isLast {
                    Button("Skip") { finish() }
                        .font(Mono.sans(.body, weight: .medium)).foregroundStyle(Mono.dim)
                        .transition(.opacity)
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 20)
            .animation(Motion.snap, value: isLast)

            TabView(selection: $page) {
                ForEach(pages) { p in
                    TourPageView(page: p, active: page == p.id).tag(p.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .sensoryFeedback(.selection, trigger: page)

            dots.padding(.vertical, 14)

            VStack(spacing: 10) {
                if isLast {
                    Button {
                        opening = true
                        Task { await app.openDemo(); finish() }
                    } label: {
                        HStack(spacing: 8) {
                            if opening { ProgressView().tint(Mono.bg) }
                            Text("Try the demo roll")
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button("Start using Unroll") { finish() }
                        .buttonStyle(SecondaryButtonStyle())
                } else {
                    Button { withAnimation(Motion.glide) { page += 1 } } label: {
                        Text(page == 0 ? "Show me around" : "Next")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
            }
            .disabled(opening)
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            .animation(Motion.snap, value: isLast)
        }
        .background(Mono.canvas.ignoresSafeArea())
        .interactiveDismissDisabled()
    }

    private var dots: some View {
        HStack(spacing: 6) {
            ForEach(pages) { p in
                Capsule()
                    .fill(p.id == page ? Mono.accent : Mono.faint)
                    .frame(width: p.id == page ? 18 : 6, height: 6)
                    .onTapGesture { withAnimation(Motion.glide) { page = p.id } }
            }
        }
        .animation(Motion.snap, value: page)
        .accessibilityElement()
        .accessibilityLabel("Page \(page + 1) of \(pages.count)")
    }

    private func finish() {
        app.settings.hasOnboarded = true
        app.showTour = false
        dismiss()
    }
}

private struct TourPageView: View {
    let page: TourPage
    let active: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
                    .padding(.bottom, 28)

                Text(page.kicker.uppercased())
                    .font(Mono.sans(.footnote, weight: .semibold)).foregroundStyle(Mono.accent)
                    .tracking(0.6)
                Text(page.title)
                    .font(Mono.sans(.title, weight: .bold))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                Text(page.body)
                    .font(Mono.sans(.body)).foregroundStyle(Mono.dim)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)

                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(page.tips.enumerated()), id: \.offset) { i, tip in
                        HStack(alignment: .top, spacing: 14) {
                            Image(systemName: tip.symbol)
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(Mono.ink)
                                .frame(width: 24, height: 22)
                            Text(tip.text)
                                .font(Mono.sans(.subheadline))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .opacity(active ? 1 : 0)
                        .offset(y: active ? 0 : 12)
                        .animation(Motion.arrive.delay(0.12 + Double(i) * 0.06), value: active)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Mono.surface, in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
                .padding(.top, 22)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private var hero: some View {
        ZStack {
            Circle().fill(Mono.accent.opacity(0.10)).frame(width: 168, height: 168)
            Circle().strokeBorder(Mono.accent.opacity(0.25), lineWidth: 1).frame(width: 124, height: 124)
            Image(systemName: page.symbol)
                .font(.system(size: 52, weight: .regular))
                .foregroundStyle(Mono.accent)
                .symbolEffect(.bounce, options: .nonRepeating, value: active)
        }
        .scaleEffect(active ? 1 : 0.86)
        .opacity(active ? 1 : 0.4)
        .animation(Motion.arrive, value: active)
        .accessibilityHidden(true)
    }
}
