import SwiftUI
import PhotosUI
import CoreLocation

struct HomeView: View {
    @Environment(AppState.self) private var app
    @State private var pickingFolder = false
    @State private var showSettings = false
    @State private var framePick: PhotosPickerItem?
    @State private var framing: FrameSubject?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ConnectHero()
                sourceButtons
                PhotoWalkCard()
                tools
                if !app.pro.isPro { ProBanner() }
                privacyNote
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 120)
        }
        .navigationTitle("Unroll")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }
            }
        }
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

    private var sourceButtons: some View {
        HStack(spacing: 10) {
            SourceButton(title: "SD Card", subtitle: "or Files", symbol: "sdcard.fill") { pickingFolder = true }
            SourceButton(title: "Ricoh GR", subtitle: "Wi-Fi beta", symbol: "wifi") { Task { await app.connectRicoh() } }
            SourceButton(title: "Demo Roll", subtitle: "try it now", symbol: "sparkles") { Task { await app.openDemo() } }
        }
    }

    private var tools: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            NavigationLink { RecipesView() } label: {
                ToolTile(symbol: "camera.filters", title: "Recipes", detail: app.recipes.items.isEmpty ? "Collected as you import" : "\(app.recipes.items.count) saved")
            }
            NavigationLink { ClockSyncView() } label: {
                ToolTile(symbol: "clock.badge.checkmark", title: "Clock Sync", detail: app.settings.clockOffsets.isEmpty ? "Fix camera time drift" : "\(app.settings.clockOffsets.count) camera(s) synced")
            }
            PhotosPicker(selection: $framePick, matching: .images, preferredItemEncoding: .current) {
                ToolTile(symbol: "rectangle.portrait.on.rectangle.portrait", title: "Frame Studio", detail: "EXIF frames for any photo")
            }
            NavigationLink { WalksView() } label: {
                ToolTile(symbol: "map", title: "Walks & Recaps", detail: "\(app.location.walks.count) walks")
            }
        }
        .buttonStyle(.plain)
    }

    private var privacyNote: some View {
        Label("No account. No cloud. Your originals are saved bit-for-bit — recipes and maker notes intact.", systemImage: "lock.shield")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.top, 4)
    }
}

struct FrameSubject: Identifiable {
    let id = UUID()
    let image: UIImage
    let meta: ShotMeta?
    var location: CLLocation? = nil
}

/// The big "plug it in" card. Pulses while listening for a camera.
struct ConnectHero: View {
    @Environment(AppState.self) private var app
    @State private var pulse = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                ZStack {
                    Circle().fill(Theme.accent.opacity(0.18)).frame(width: 64, height: 64)
                        .scaleEffect(pulse ? 1.25 : 0.9).opacity(pulse ? 0 : 1)
                    Circle().fill(Theme.accent.opacity(0.22)).frame(width: 56, height: 56)
                    Image(systemName: "cable.connector.horizontal").font(.title2.weight(.semibold)).foregroundStyle(Theme.accent)
                }
                Spacer()
                Label(app.usb.authorization == .denied ? "Access off" : "Listening", systemImage: app.usb.authorization == .denied ? "exclamationmark.triangle" : "dot.radiowaves.left.and.right")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(.white.opacity(0.08), in: Capsule())
                    .foregroundStyle(app.usb.authorization == .denied ? .orange : .secondary)
            }
            Text("Plug in your camera").font(.title.bold())
            Text("USB-C cable or card reader. Fujifilm, Sony, Canon, Nikon, Leica, Ricoh, OM, Panasonic — no pairing, no Bluetooth, no account. Newest shots appear first.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Pill(text: "RAW + JPEG", symbol: "square.stack.3d.up")
                Pill(text: "★ & lock", symbol: "line.3.horizontal.decrease")
                Pill(text: "Auto GPS", symbol: "location")
            }
            if app.usb.authorization == .denied {
                Button("Allow camera access in Settings") {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }
                .font(.footnote.weight(.semibold))
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(colors: [Theme.cardHi, Theme.card], startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(.white.opacity(0.06)))
        .onAppear { withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) { pulse = true } }
    }
}

struct Pill: View {
    let text: String
    let symbol: String
    var body: some View {
        Label(text, systemImage: symbol)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(.white.opacity(0.07), in: Capsule())
            .lineLimit(1)
    }
}

struct SourceButton: View {
    let title: String
    let subtitle: String
    let symbol: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: symbol).font(.title3).foregroundStyle(Theme.accent)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(subtitle).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

struct ToolTile: View {
    let symbol: String
    let title: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Theme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading)
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .contentShape(Rectangle())
    }
}

struct PhotoWalkCard: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let rec = app.location
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            HStack(spacing: 14) {
                Image(systemName: rec.isRecording ? "figure.walk.motion" : "figure.walk")
                    .font(.title2)
                    .foregroundStyle(rec.isRecording ? .green : Theme.accent)
                    .frame(width: 44, height: 44)
                    .background((rec.isRecording ? Color.green : Theme.accent).opacity(0.15), in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(rec.isRecording ? "Photo walk in progress" : "Start a photo walk").font(.headline)
                    if let w = rec.current {
                        Text("\(Duration.seconds(w.duration).formatted(.time(pattern: .hourMinuteSecond)))  ·  \(String(format: "%.2f km", w.distanceMeters / 1000))  ·  \(w.points.count) fixes")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    } else {
                        Text("Light GPS log on your phone → every import gets a location. No camera Bluetooth needed.")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                Button(rec.isRecording ? "Stop" : "Start") {
                    rec.isRecording ? rec.stopWalk() : rec.startWalk()
                    Haptics.tap()
                }
                .buttonStyle(.borderedProminent)
                .tint(rec.isRecording ? .red : Theme.accent)
                .controlSize(.small)
            }
            .padding(14)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}

struct ProBanner: View {
    @Environment(AppState.self) private var app
    var body: some View {
        Button { app.showPaywall = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "bolt.fill").foregroundStyle(.black)
                    .frame(width: 36, height: 36).background(Theme.accent, in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text("Unroll Pro").font(.headline)
                    Text("Unlimited imports · auto-pull ★ shots · all frames · no watermark")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer()
                Text("\(app.quota.remaining)/\(Quota.freePerDay) left today")
                    .font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            }
            .padding(14)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.accent.opacity(0.35)))
        }
        .buttonStyle(.plain)
    }
}
