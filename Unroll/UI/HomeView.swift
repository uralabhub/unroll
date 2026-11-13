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
                    .padding(.bottom, 20)

                ConnectHero()
                    .rise(0, distance: 28)

                blockTitle("Or import from").rise(1)
                LazyVGrid(columns: pair, spacing: 10) {
                    Button { pickingFolder = true } label: {
                        Tile(symbol: "sd-card", title: "SD card", caption: "Card reader or Files")
                    }
                    .rise(2)
                    Button { Task { await app.connectRicoh() } } label: {
                        Tile(symbol: "wifi-high", title: "Ricoh GR", caption: "Over the camera’s Wi-Fi", tag: "Beta")
                    }
                    .rise(3)
                }

                blockTitle("Photo walk").rise(4)
                PhotoWalkCard().rise(5).scrollDepth()

                blockTitle("Tools").rise(6)
                LazyVGrid(columns: pair, spacing: 10) {
                    NavigationLink { RecipesView() } label: {
                        Tile(symbol: "film-strip", title: "Recipes", caption: "Saved from your shots",
                             count: app.recipes.items.count)
                    }
                    .rise(7).scrollDepth()
                    NavigationLink { ClockSyncView() } label: {
                        Tile(symbol: "clock", title: "Clock sync", caption: "Fix camera time drift",
                             count: app.settings.clockOffsets.count)
                    }
                    .rise(8).scrollDepth()
                    PhotosPicker(selection: $framePick, matching: .images, preferredItemEncoding: .current) {
                        Tile(symbol: "frame-corners", title: "Frame", caption: "EXIF border, any photo")
                    }
                    .rise(9).scrollDepth()
                    NavigationLink { WalksView() } label: {
                        Tile(symbol: "map-trifold", title: "Walks", caption: "Routes and recap cards",
                             count: app.location.walks.count)
                    }
                    .rise(10).scrollDepth()
                }

                if !app.pro.isPro {
                    QuotaCard().padding(.top, 28).rise(11).scrollDepth()
                }

                Text("No account · No cloud · Originals untouched".uppercased())
                    .font(Mono.caps(10)).tracking(1.2)
                    .foregroundStyle(Mono.faint)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 32)
            }
            .buttonStyle(TileButtonStyle())
            .padding(.horizontal, 16)
            .padding(.bottom, 120)
        }
        .background(Mono.canvas)
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
                Wordmark()
            }
            Spacer()
            Button { showSettings = true } label: {
                Icon("gear-six", size: 20)
                    .frame(width: 42, height: 42)
                    .background(Mono.surface, in: Circle())
            }
            .buttonStyle(PressStyle(scale: 0.9, lifted: true))
            .accessibilityLabel("Settings")
            .padding(.bottom, 4)
        }
        .padding(.top, 8)
        .padding(.horizontal, 4)
    }

    private func blockTitle(_ text: String) -> some View {
        CapsLabel(text)
            .padding(.horizontal, 4)
            .padding(.top, 28)
            .padding(.bottom, 10)
    }
}

struct FrameSubject: Identifiable {
    let id = UUID()
    let image: UIImage
    let meta: ShotMeta?
    var location: CLLocation? = nil
}

// MARK: - Pieces

/// Pressed state for tiles and cards: sinks on a spring and its shadow tightens.
struct TileButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PressStyle(scale: 0.96, lifted: true).makeBody(configuration: configuration)
    }
}

/// "Unroll" set in Nunito, revealed left to right like film pulled off a spool.
struct Wordmark: View {
    @State private var unrolled = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Text("Unroll")
            .font(Mono.sans(size: 40, weight: .heavy))
            .tracking(-1.2)
            .mask(alignment: .leading) {
                GeometryReader { g in
                    Rectangle().frame(width: unrolled || reduce ? g.size.width + 8 : 0)
                }
            }
            .offset(x: unrolled || reduce ? 0 : -6)
            .onAppear {
                withAnimation(.spring(response: 0.9, dampingFraction: 0.88).delay(0.1)) { unrolled = true }
            }
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
                Icon(symbol, size: 24)
                    .frame(height: 24, alignment: .leading)
                Spacer()
                if let tag {
                    Text(tag.uppercased())
                        .font(Mono.caps(9)).tracking(1)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .overlay(Capsule().strokeBorder(Mono.ink.opacity(0.3), lineWidth: 1))
                } else if count > 0 {
                    Text("\(count)").font(Mono.data(.footnote, weight: .medium)).foregroundStyle(Mono.dim)
                        .rollingNumber(count)
                }
            }
            Spacer(minLength: 18)
            Text(title).font(Mono.sans(.body, weight: .bold))
            if let caption {
                Text(caption).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim).lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 116, alignment: .topLeading)
        .background(Mono.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Top of Home: a dark strip of film that says what to do.
struct ConnectHero: View {
    @Environment(AppState.self) private var app
    @Environment(\.colorScheme) private var scheme

    private var denied: Bool { app.usb.authorization == .denied }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Sprockets(reverse: false)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Ping(active: !denied)
                    Text((denied ? "Camera access off" : "Waiting for a camera").uppercased())
                        .font(Mono.caps(10)).tracking(1.2)
                        .foregroundStyle(.white.opacity(0.7))
                    Spacer()
                    Text("USB-C").font(Mono.caps(10)).tracking(1.2).foregroundStyle(.white.opacity(0.4))
                }

                // Two lines stacked by hand: Nunito's line height is tall and SwiftUI ignores negative lineSpacing.
                VStack(alignment: .leading, spacing: -4) {
                    Text("Plug in")
                    Text("your camera.")
                }
                .font(Mono.sans(size: 34, weight: .bold))
                .tracking(-1.0)
                .foregroundStyle(.white)
                .padding(.top, 20)

                Text("Newest shots load first. RAW, star ratings and locked frames come across as they are.")
                    .font(Mono.sans(.subheadline))
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
                                Icon("arrow-right", size: 20)
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
            Sprockets(reverse: false)
        }
        .background(Color(white: scheme == .dark ? 0.13 : 0.07), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(.white.opacity(scheme == .dark ? 0.08 : 0), lineWidth: 1))
        .environment(\.colorScheme, .dark)
        .depth(1.6)
        .tilt(4)
    }
}

/// Status dot with a ring that keeps expanding outward, like a radar waiting for a signal.
private struct Ping: View {
    let active: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            if active && !reduce {
                TimelineView(.animation) { ctx in
                    let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6
                    Circle().stroke(.white, lineWidth: 1)
                        .frame(width: 6, height: 6)
                        .scaleEffect(1 + t * 2.6)
                        .opacity(0.7 * (1 - t))
                }
            }
            Circle().fill(.white).frame(width: 6, height: 6).opacity(active ? 1 : 0.4)
        }
        .frame(width: 22, height: 10)
        .padding(.leading, -8)
    }
}

/// Row of film perforations along the hero's edge. They creep sideways, as if the strip is
/// slowly being wound through a camera.
private struct Sprockets: View {
    var reverse: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce
    private let pitch: CGFloat = 22, hole = CGSize(width: 10, height: 6), speed: Double = 9

    var body: some View {
        TimelineView(.animation(paused: reduce)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            let phase = CGFloat((t * speed).truncatingRemainder(dividingBy: Double(pitch))) * (reverse ? -1 : 1)
            Canvas { g, size in
                var x = -pitch + phase
                while x < size.width + pitch {
                    let r = CGRect(x: x, y: (size.height - hole.height) / 2, width: hole.width, height: hole.height)
                    g.fill(Path(roundedRect: r, cornerRadius: 1.5), with: .color(.white.opacity(0.14)))
                    x += pitch
                }
            }
        }
        .frame(height: 26)
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                     .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                             startPoint: .leading, endPoint: .trailing))
        .padding(.horizontal, 8)
        .accessibilityHidden(true)
    }
}

private struct HeroButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Mono.sans(.subheadline, weight: .semibold))
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(.white, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(Motion.press, value: configuration.isPressed)
            .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: configuration.isPressed) { _, new in new }
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
                                .transition(.scale.combined(with: .opacity))
                        }
                        Text(rec.isRecording ? "RECORDING" : "GPS LOG")
                            .font(Mono.caps(10)).tracking(1.2)
                            .foregroundStyle(rec.isRecording ? Mono.ink : Mono.dim)
                    }
                    Text(Duration.seconds(w?.duration ?? 0).formatted(.time(pattern: .hourMinuteSecond(padHourToLength: 2))))
                        .font(.system(size: 30, weight: .regular, design: .monospaced))
                        .monospacedDigit()
                        .foregroundStyle(w == nil ? Mono.faint : Mono.ink)
                        .contentTransition(.numericText())
                    if let w {
                        Text(String(format: "%.2f km  ·  %d fixes", w.distanceMeters / 1000, w.points.count))
                            .font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                    } else {
                        Text("Log your route while you shoot. Imports get locations on their own.")
                            .font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
                Button {
                    rec.isRecording ? rec.stopWalk() : rec.startWalk()
                    Haptics.tap()
                } label: {
                    ZStack {
                        if rec.isRecording { RecordingRings() }
                        Icon(rec.isRecording ? "stop-fill" : "play-fill", size: 22)
                            .id(rec.isRecording)
                            .transition(.scale(scale: 0.4).combined(with: .opacity))
                            .foregroundStyle(Mono.bg)
                            .frame(width: 58, height: 58)
                            .background(Mono.ink, in: Circle())
                    }
                    .animation(Motion.snap, value: rec.isRecording)
                }
                .buttonStyle(PressStyle(scale: 0.9))
                .accessibilityLabel(rec.isRecording ? "Stop walk" : "Start walk")
            }
            .padding(16)
            .background(Mono.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .depth()
        }
    }
}

/// Two rings breathing out from the stop button while a walk is being logged.
private struct RecordingRings: View {
    @Environment(\.accessibilityReduceMotion) private var reduce
    var body: some View {
        TimelineView(.animation(paused: reduce)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            ZStack {
                ForEach(0..<2, id: \.self) { i in
                    let p = ((t / 2.2) + Double(i) * 0.5).truncatingRemainder(dividingBy: 1)
                    Circle().stroke(Mono.ink, lineWidth: 1)
                        .frame(width: 58, height: 58)
                        .scaleEffect(1 + p * 0.55)
                        .opacity(0.45 * (1 - p))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Free tier meter: ten segments, one per import left today.
struct QuotaCard: View {
    @Environment(AppState.self) private var app
    @State private var filled = false

    var body: some View {
        let left = app.quota.remaining
        let total = Quota.freePerDay
        let fill = filled ? left : 0
        Button { app.showPaywall = true } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("FREE TODAY").font(Mono.caps(10)).tracking(1.2).foregroundStyle(Mono.dim)
                    Spacer()
                    Text("\(left) of \(total) left").font(Mono.data(.caption, weight: .medium))
                        .rollingNumber(left)
                }
                HStack(spacing: 4) {
                    ForEach(0..<total, id: \.self) { i in
                        Capsule()
                            .fill(Mono.ink.opacity(0.12))
                            .overlay(alignment: .leading) {
                                Capsule().fill(Mono.ink)
                                    .scaleEffect(x: i < fill ? 1 : 0, anchor: .leading)
                                    .animation(Motion.arrive.delay(Motion.stagger(i, step: 0.05)), value: fill)
                            }
                            .frame(height: 4)
                    }
                }
                HStack {
                    Text("Unlimited imports, auto-import picks, every frame, no watermark.")
                        .font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 12)
                    Text("Get Pro")
                        .font(Mono.sans(.footnote, weight: .semibold))
                        .foregroundStyle(Mono.bg)
                        .padding(.horizontal, 14).frame(height: 32)
                        .background(Mono.ink, in: Capsule())
                }
            }
            .padding(16)
            .background(Mono.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(Rectangle())
        }
        .onAppear { filled = true }
    }
}
