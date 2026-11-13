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

                blockTitle("Or import from")
                LazyVGrid(columns: pair, spacing: 10) {
                    Button { pickingFolder = true } label: {
                        Tile(symbol: "sdcard", title: "SD card", caption: "Reader or Files")
                    }
                    Button { Task { await app.connectRicoh() } } label: {
                        Tile(symbol: "wifi", title: "Ricoh GR", caption: "Camera Wi-Fi", tag: "Beta")
                    }
                }

                blockTitle("Photo walk")
                PhotoWalkCard()

                blockTitle("Tools")
                LazyVGrid(columns: pair, spacing: 10) {
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

                if !app.pro.isPro {
                    QuotaCard().padding(.top, 28)
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
                    .font(.sans(size: 40, weight: .bold))
                    .tracking(-1.2)
            }
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 40, height: 40)
                    .background(Mono.tile, in: Circle())
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
                Image(systemName: symbol)
                    .symbolRenderingMode(.monochrome)
                    .font(.system(size: 20, weight: .regular))
                    .frame(height: 24, alignment: .leading)
                Spacer()
                if let tag {
                    Text(tag.uppercased())
                        .font(Mono.caps(9)).tracking(1)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .overlay(Capsule().strokeBorder(Mono.ink.opacity(0.3), lineWidth: 1))
                } else if count > 0 {
                    Text("\(count)").font(Mono.data(.footnote, weight: .medium)).foregroundStyle(Mono.dim)
                }
            }
            Spacer(minLength: 18)
            Text(title).font(.sans(.body, .semibold))
            if let caption {
                Text(caption).font(.sans(.footnote)).foregroundStyle(Mono.dim).lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 116, alignment: .topLeading)
        .background(Mono.tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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
            Sprockets()
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

                Text("Plug in\nyour camera.")
                    .font(.sans(size: 36, weight: .semibold))
                    .tracking(-1)
                    .lineSpacing(-2)
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
                                Image(systemName: "arrow.right")
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
            Sprockets()
        }
        .background(Color(white: scheme == .dark ? 0.13 : 0.07), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .environment(\.colorScheme, .dark)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { blink = true }
        }
    }
}

/// Row of film perforations along the hero's edge.
private struct Sprockets: View {
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<16, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(.white.opacity(0.13))
                    .frame(width: 10, height: 6)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
    }
}

private struct HeroButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.sans(.subheadline, .semibold))
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(.white.opacity(configuration.isPressed ? 0.8 : 1),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
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
                        .font(.system(size: 30, weight: .regular, design: .monospaced))
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
                    Image(systemName: rec.isRecording ? "stop.fill" : "play.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Mono.bg)
                        .frame(width: 56, height: 56)
                        .background(Mono.ink, in: Circle())
                }
                .buttonStyle(TileButtonStyle())
                .accessibilityLabel(rec.isRecording ? "Stop walk" : "Start walk")
            }
            .padding(16)
            .background(Mono.tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
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
                    Text("\(left) of \(total) left").font(Mono.data(.caption, weight: .medium))
                }
                HStack(spacing: 4) {
                    ForEach(0..<total, id: \.self) { i in
                        Capsule()
                            .fill(i < left ? Mono.ink : Mono.ink.opacity(0.12))
                            .frame(height: 4)
                    }
                }
                HStack {
                    Text("Unlimited imports, auto-import picks, every frame, no watermark.")
                        .font(.sans(.footnote)).foregroundStyle(Mono.dim)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 12)
                    Text("Get Pro")
                        .font(.sans(.footnote, .semibold))
                        .foregroundStyle(Mono.bg)
                        .padding(.horizontal, 14).frame(height: 32)
                        .background(Mono.ink, in: Capsule())
                }
            }
            .padding(16)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Mono.line, lineWidth: 1))
            .contentShape(Rectangle())
        }
    }
}
