import SwiftUI
import PhotosUI
import CoreLocation

/// Where tools live. Reached from Home's list and from the library's menu.
enum ToolRoute: Hashable {
    case recipes, clockSync, walks, settings
}

extension View {
    func toolDestinations() -> some View {
        navigationDestination(for: ToolRoute.self) { r in r.destination }
    }
}

extension ToolRoute {
    @ViewBuilder @MainActor var destination: some View {
        switch self {
        case .recipes: RecipesView()
        case .clockSync: ClockSyncView()
        case .walks: WalksView()
        case .settings: SettingsView()
        }
    }
}

/// The screen when nothing is connected: what to do (plug in), the other ways in, and the tools.
/// One calm list; no animation runs while it sits there.
struct HomeView: View {
    @Environment(AppState.self) private var app
    @State private var pickingFolder = false
    @State private var framePick: PhotosPickerItem?
    @State private var framing: FrameSubject?

    var body: some View {
        List {
            Section {
                ConnectPanel(pickFolder: { pickingFolder = true })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Mono.surface)
            }

            Section {
                PhotoWalkRow().rise(4)
                NavigationLink(value: ToolRoute.walks) {
                    ToolRow(icon: "map-trifold", title: "Walk history", value: count(app.location.walks.count))
                }
                .rise(5)
                NavigationLink(value: ToolRoute.recipes) {
                    ToolRow(icon: "film-strip", title: "Recipes", value: count(app.recipes.items.count))
                }
                .rise(6)
                NavigationLink(value: ToolRoute.clockSync) {
                    ToolRow(icon: "clock", title: "Clock sync", value: count(app.settings.clockOffsets.count))
                }
                .rise(7)
                PhotosPicker(selection: $framePick, matching: .images, preferredItemEncoding: .current) {
                    ToolRow(icon: "frame-corners", title: "Frame a photo", value: nil)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.97))
                .rise(8)
            } header: {
                Text("Tools")
            }

            if !app.pro.isPro {
                Section {
                    Button { app.showPaywall = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(app.quota.remaining) of \(Quota.freePerDay) free imports left today")
                                    .font(Mono.sans(.subheadline)).monospacedDigit()
                                    .rollingNumber(app.quota.remaining)
                                Text("Pro: unlimited imports, auto-import picks, no watermark")
                                    .font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                            }
                            Spacer()
                            Text("Get Pro").font(Mono.sans(.subheadline, weight: .semibold))
                                .foregroundStyle(Mono.accent)
                        }
                    }
                    .buttonStyle(PressStyle(scale: 0.97))
                    .rise(9)
                } footer: {
                    Text("No account. No cloud. Your originals are never modified.")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Mono.canvas)
        .navigationTitle("Unroll")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { app.showTour = true } label: { Image(systemName: "questionmark.circle").font(.system(size: 20)) }
                    .accessibilityLabel("How Unroll works")
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: ToolRoute.settings) { Icon("gear-six", size: 22) }
                    .accessibilityLabel("Settings")
            }
        }
        .toolDestinations()
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { Task { await app.openFolder(url) } }
        }
        .onChange(of: framePick) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    let decoded = await Task.detached(priority: .userInitiated) { () -> (UIImage?, ShotMeta) in
                        (ImageDecode.downsample(data, maxPixel: 6000), HeaderParser.parse(data.prefix(HeaderParser.headerLength)).meta)
                    }.value
                    if let img = decoded.0 { framing = FrameSubject(image: img, meta: decoded.1) }
                }
                framePick = nil
            }
        }
        .fullScreenCover(item: $framing) { s in FrameStudioView(image: s.image, meta: s.meta, location: s.location) }
    }

    private func count(_ n: Int) -> String? { n > 0 ? "\(n)" : nil }
}

struct FrameSubject: Identifiable {
    let id = UUID()
    let image: UIImage
    let meta: ShotMeta?
    var location: CLLocation? = nil
}

// MARK: - Pieces

/// Icon + title + optional value, in a list row.
struct ToolRow: View {
    let icon: String
    let title: String
    let value: String?
    var body: some View {
        HStack(spacing: 14) {
            Icon(icon, size: 20).foregroundStyle(Mono.dim).frame(width: 24)
            Text(title).font(Mono.sans(.body))
            Spacer()
            if let value {
                Text(value).font(Mono.data(.body)).foregroundStyle(Mono.dim)
                    .rollingText(value)
                    .transition(.popIn)
            }
        }
        .frame(minHeight: 32)
        .animation(Motion.snap, value: value)
    }
}

/// "Connect your camera": the one thing to do, then the other ways in.
struct ConnectPanel: View {
    @Environment(AppState.self) private var app
    let pickFolder: () -> Void
    @State private var opening: String?

    private var denied: Bool { app.usb.authorization == .denied }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(denied ? Mono.faint : Mono.accent).frame(width: 7, height: 7)
                    .pulseRing(!denied)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                Text(denied ? "Camera access is off" : "Ready. Waiting for a camera")
                    .font(Mono.sans(.footnote, weight: .medium)).foregroundStyle(Mono.dim)
                    .contentTransition(.opacity)
            }
            .animation(Motion.snap, value: denied)
            .rise(0)
            HStack(alignment: .center, spacing: 12) {
                Text("Connect your camera")
                    .font(Mono.sans(.title2, weight: .bold))
                Spacer(minLength: 0)
                Icon("usb", size: 26).foregroundStyle(Mono.dim)
                    .breathe(!denied)
            }
            .padding(.top, 14)
            .rise(1)
            Text("Plug it in with a USB‑C cable. Newest photos show up first; RAW, star ratings and protected shots come across as they are. Nothing to pair.")
                .font(Mono.sans(.subheadline)).foregroundStyle(Mono.dim)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 6)
                .rise(2)

            if denied {
                Button("Allow camera access in Settings") {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 18)
            }

            // Brand-specific ways in (Ricoh GR over Wi‑Fi) live in Settings › Connections, not here.
            Button(action: pickFolder) {
                Label { Text("Open an SD card") } icon: { Icon("sd-card", size: 18) }
            }
            .buttonStyle(SecondaryButtonStyle())
            .padding(.top, 20)
            .rise(3)

            Button { open("demo") { await app.openDemo() } } label: {
                HStack(spacing: 8) {
                    if opening == "demo" { ProgressView().controlSize(.small) }
                    Text("No camera handy? Try the demo roll")
                }
                    .font(Mono.sans(.footnote, weight: .medium))
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(scale: 0.97))
            .foregroundStyle(Mono.dim)
            .padding(.top, 8)
            .rise(4)
        }
        .padding(20)
        .disabled(opening != nil)
    }

    private func open(_ key: String, _ work: @escaping () async -> Void) {
        opening = key
        Haptics.tap()
        Task { await work(); opening = nil }
    }
}

/// Photo walk on/off in a list row; the timer only ticks while recording.
struct PhotoWalkRow: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let rec = app.location
        HStack(spacing: 14) {
            Image(systemName: "figure.walk").font(.system(size: 18)).foregroundStyle(rec.isRecording ? Mono.accent : Mono.dim).frame(width: 24)
                .symbolEffect(.pulse, isActive: rec.isRecording)
                .symbolEffect(.bounce, value: rec.isRecording)
            VStack(alignment: .leading, spacing: 2) {
                    Text("Photo walk").font(Mono.sans(.body))
                    if rec.isRecording, let w = rec.current {
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            let line = "Recording · \(Duration.seconds(w.duration).formatted(.time(pattern: .hourMinuteSecond))) · \(String(format: "%.2f km", w.distanceMeters / 1000))"
                            Text(line)
                                .font(Mono.data(.footnote)).foregroundStyle(Mono.accent)
                                .rollingText(line)
                        }
                    } else {
                        Text("Log your route; imports get locations").font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                            .transition(.swapUp)
                    }
            }
            .animation(Motion.snap, value: rec.isRecording)
            Spacer(minLength: 8)
            Button(rec.isRecording ? "Stop" : "Start") {
                rec.isRecording ? rec.stopWalk() : rec.startWalk()
                Haptics.tap()
            }
            .buttonStyle(OutlineChipStyle(filled: rec.isRecording))
            .animation(Motion.snap, value: rec.isRecording)
        }
    }
}
