import SwiftUI
import PhotosUI
import CoreLocation

struct HomeView: View {
    @Environment(AppState.self) private var app
    @State private var pickingFolder = false
    @State private var showSettings = false
    @State private var framePick: PhotosPickerItem?
    @State private var framing: FrameSubject?

    private let pair = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.bottom, 18)
                    .riseIn(0)

                ConnectHero()
                    .riseIn(1)
                    // Parallax: the hero settles back and fades as the page scrolls over it.
                    .scrollTransition(.interactive, axis: .vertical) { content, phase in
                        content
                            .scaleEffect(phase.value < 0 ? 1 + phase.value * 0.06 : 1)
                            .opacity(phase.value < 0 ? 1 + phase.value * 0.6 : 1)
                            .offset(y: phase.value < 0 ? -phase.value * 40 : 0)
                    }

                blockTitle("Or import from")
                LazyVGrid(columns: pair, spacing: 12) {
                    Button { pickingFolder = true } label: {
                        Tile(symbol: "sdcard", title: "SD card", caption: "Reader or Files")
                    }
                    Button { Task { await app.connectRicoh() } } label: {
                        Tile(symbol: "wifi", title: "Ricoh GR", caption: "Camera Wi-Fi", tag: "Beta")
                    }
                }

                .riseIn(3)

                blockTitle("Photo walk")
                PhotoWalkCard().riseIn(5)

                blockTitle("Tools")
                LazyVGrid(columns: pair, spacing: 12) {
                    NavigationLink { RecipesView() } label: {
                        Tile(symbol: "camera.filters", title: "Recipes", caption: "From your shots",
                             count: app.recipes.items.count)
                    }
                    NavigationLink { ClockSyncView() } label: {
                        Tile(symbol: "clock", title: "Clock sync", caption: "Fix time drift",
                             count: app.settings.clockOffsets.count)
                    }
                    PhotosPicker(selection: $framePick, matching: .images, preferredItemEncoding: .current) {
                        Tile(symbol: "photo.artframe", title: "Frame", caption: "EXIF border")
                    }
                    NavigationLink { WalksView() } label: {
                        Tile(symbol: "map", title: "Walks", caption: "Routes, recaps",
                             count: app.location.walks.count)
                    }
                }
                .riseIn(7)

                if !app.pro.isPro {
                    QuotaCard().padding(.top, 28).riseIn(8)
                }

                Text("No account · No cloud · Originals untouched".uppercased())
                    .font(Mono.caps(10)).tracking(1.2)
                    .foregroundStyle(Mono.faint)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 32)
            }
            .buttonStyle(PressStyle())
            .padding(.horizontal, 16)
            .padding(.bottom, 120)
        }
        .background(Mono.bg)
        .toolbar(.hidden, for: .navigationBar)
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { Task { await app.openFolder(url) } }
        }
        .sheet(isPresented: $showSettings) { NavigationStack { SettingsView() } }
        .onChange(of: framePick) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let img = ImageDecode.downsample(data, maxPixel: 8000) {
                    let meta = HeaderParser.parse(data.prefix(HeaderParser.headerLength)).meta
                    framing = FrameSubject(image: img, meta: meta)
                }
                framePick = nil
            }
        }
        .fullScreenCover(item: $framing) { s in FrameStudioView(image: s.image, meta: s.meta, location: s.location) }
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Date.now.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)).uppercased())
                    .font(Mono.caps(11)).tracking(1.2)
                    .foregroundStyle(Mono.dim)
                Text("Unroll")
                    .font(.sans(size: 40, weight: .heavy))
                    .tracking(-1)
            }
            Spacer()
            Button { showSettings = true } label: {
                Ph("gearshape", size: 21)
                    .frame(width: 44, height: 44)
                    .background(Mono.surface, in: Circle())
                    .shadow(color: Mono.shadow, radius: 8, y: 3)
            }
            .buttonStyle(TileButtonStyle())
            .accessibilityLabel("Settings")
            .padding(.bottom, 4)
        }
        .padding(.top, 8)
        .padding(.horizontal, 4)
    }

    private func blockTitle(_ text: String) -> some View {
        CapsLabel(text)
            .padding(.horizontal, 6)
            .padding(.top, 30)
            .padding(.bottom, 12)
    }
}

struct FrameSubject: Identifiable {
    let id = UUID()
    let image: UIImage
    let meta: ShotMeta?
    var location: CLLocation? = nil
}

// MARK: - Pieces

/// Pressed state for tiles and cards: a slight sink, no colour.
struct TileButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Mono.ink)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.18), value: configuration.isPressed)
    }
}

/// Square-ish grey tile: glyph top-left, optional count or tag top-right, title and caption at the bottom.
struct Tile: View {
    let symbol: String
    let title: String
    var caption: String? = nil
    var count: Int = 0
    var tag: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                Ph(symbol, size: 26)
                Spacer()
                if let tag {
                    Text(tag.uppercased())
                        .font(Mono.caps(9)).tracking(1)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Mono.raised, in: Capsule())
                } else if count > 0 {
                    Text("\(count)").font(Mono.data(.subheadline, weight: .bold)).foregroundStyle(Mono.dim)
                        .contentTransition(.numericText(value: Double(count)))
                }
            }
            Spacer(minLength: 20)
            Text(title).font(.sans(.headline, .heavy))
            if let caption {
                Text(caption).font(.sans(.footnote)).foregroundStyle(Mono.dim).lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 124, alignment: .topLeading)
        .card()
        .contentShape(RoundedRectangle(cornerRadius: Mono.cardRadius, style: .continuous))
    }
}

/// Top of Home: a dark strip of film that says what to do.
struct ConnectHero: View {
    @Environment(AppState.self) private var app
    @Environment(\.colorScheme) private var scheme
    @State private var blink = false

    private var denied: Bool { app.usb.authorization == .denied }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Sprockets(fast: app.usb.authorization == .authorized && app.session != nil)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(.white)
                        .frame(width: 6, height: 6)
                        .opacity(denied ? 0.4 : (blink ? 0.15 : 1))
                    Text((denied ? "Camera access off" : "Waiting for a camera").uppercased())
                        .font(Mono.caps(10)).tracking(1.2)
                        .foregroundStyle(.white.opacity(0.7))
                    Spacer()
                    Text("USB-C").font(Mono.caps(10)).tracking(1.2).foregroundStyle(.white.opacity(0.4))
                }

                VStack(alignment: .leading, spacing: -12) {
                    Text("Plug in")
                    Text("your camera.")
                }
                    .font(.sans(size: 38, weight: .heavy))
                    .tracking(-1)
                    .foregroundStyle(.white)
                    .padding(.top, 22)

                Text("Newest shots load first. RAW, star ratings and locked frames come across as they are.")
                    .font(.sans(.subheadline))
                    .foregroundStyle(.white.opacity(0.55))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)

                Text("Fujifilm  Sony  Canon  Nikon  Leica  Ricoh  OM  Lumix".uppercased())
                    .font(Mono.caps(9)).tracking(1)
                    .foregroundStyle(.white.opacity(0.35))
                    .lineLimit(1).minimumScaleFactor(0.7)
                    .padding(.top, 18)

                Group {
                    if denied {
                        Button("Allow access in Settings") {
                            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                        }
                    } else {
                        Button { Task { await app.openDemo() } } label: {
                            HStack {
                                Text("No camera? Open the demo roll")
                                Spacer()
                                Ph("arrow.right", size: 17)
                            }
                            .padding(.horizontal, 16)
                        }
                    }
                }
                .buttonStyle(HeroButtonStyle())
                .padding(.top, 22)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 18)
            Sprockets(fast: app.usb.authorization == .authorized && app.session != nil)
        }
        .background(Mono.hero, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: Mono.shadow.opacity(2), radius: 22, y: 10)
        .environment(\.colorScheme, .dark)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { blink = true }
        }
    }
}

/// Row of film perforations along the hero's edge.
/// The perforations advance slowly, like film being wound on (faster when a camera is found).
private struct Sprockets: View {
    var fast = false
    var body: some View {
        TimelineView(.animation) { ctx in
            Canvas { g, size in
                let pitch = size.width / 15
                let period = fast ? 0.9 : 5.2
                let phase = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
                var x = -phase * pitch + (pitch - 10) / 2
                while x < size.width {
                    g.fill(Path(roundedRect: CGRect(x: x, y: (size.height - 6) / 2, width: 10, height: 6), cornerRadius: 1.5),
                           with: .color(.white.opacity(0.14)))
                    x += pitch
                }
            }
        }
        .frame(height: 26)
    }
}

private struct HeroButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.sans(.subheadline, .heavy))
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(Mono.press, value: configuration.isPressed)
    }
}

/// GPS logger: a big running clock and a single round start / stop control.
struct PhotoWalkCard: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let rec = app.location
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let w = rec.current
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        if rec.isRecording {
                            Circle().fill(Mono.ink).frame(width: 6, height: 6)
                        }
                        Text(rec.isRecording ? "RECORDING" : "GPS LOG")
                            .font(Mono.caps(10)).tracking(1.2)
                            .foregroundStyle(rec.isRecording ? Mono.ink : Mono.dim)
                    }
                    Text(Duration.seconds(w?.duration ?? 0).formatted(.time(pattern: .hourMinuteSecond(padHourToLength: 2))))
                        .font(.sans(size: 32, weight: .heavy))
                        .monospacedDigit()
                        .foregroundStyle(w == nil ? Mono.faint : Mono.ink)
                        .contentTransition(.numericText())
                    if let w {
                        Text(String(format: "%.2f km  ·  %d fixes", w.distanceMeters / 1000, w.points.count))
                            .font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                    } else {
                        Text("Log your route while you shoot. Imports get locations on their own.")
                            .font(.sans(.footnote)).foregroundStyle(Mono.dim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
                Button {
                    rec.isRecording ? rec.stopWalk() : rec.startWalk()
                    Haptics.tap()
                } label: {
                    Ph(rec.isRecording ? "stop.fill" : "play.fill", size: 22)
                        .foregroundStyle(Mono.bg)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 60, height: 60)
                        // Morphs between start (circle) and stop (rounded square).
                        .background(Mono.ink, in: RoundedRectangle(cornerRadius: rec.isRecording ? 18 : 30, style: .continuous))
                        .animation(Mono.press, value: rec.isRecording)
                }
                .buttonStyle(TileButtonStyle())
                .accessibilityLabel(rec.isRecording ? "Stop walk" : "Start walk")
            }
            .padding(18)
            .card()
        }
    }
}

/// Free tier meter: ten segments, one per import left today.
struct QuotaCard: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let left = app.quota.remaining
        let total = Quota.freePerDay
        Button { app.showPaywall = true } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("FREE TODAY").font(Mono.caps(10)).tracking(1.2).foregroundStyle(Mono.dim)
                    Spacer()
                    Text("\(left)").font(Mono.data(.subheadline, weight: .heavy))
                        .contentTransition(.numericText(value: Double(left)))
                    Text("of \(total) left").font(Mono.data(.subheadline, weight: .bold)).foregroundStyle(Mono.dim)
                }
                HStack(spacing: 4) {
                    ForEach(0..<total, id: \.self) { i in
                        Capsule().fill(Mono.raised).frame(height: 6)
                            .overlay(alignment: .leading) {
                                Capsule().fill(Mono.ink).frame(height: 6).scaleEffect(x: i < left ? 1 : 0, anchor: .leading)
                                    .animation(Mono.settle.delay(Double(i) * 0.04), value: left)
                            }
                    }
                }
                HStack {
                    Text("Unlimited imports, auto-import picks, every frame, no watermark.")
                        .font(.sans(.footnote)).foregroundStyle(Mono.dim)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 12)
                    Text("Get Pro")
                        .font(.sans(.footnote, .heavy))
                        .foregroundStyle(Mono.bg)
                        .padding(.horizontal, 14).frame(height: 32)
                        .background(Mono.ink, in: Capsule())
                }
            }
            .padding(16)
            .padding(2)
            .card()
            .contentShape(Rectangle())
        }
    }
}
