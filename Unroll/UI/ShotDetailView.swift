import SwiftUI
import CoreLocation

struct ShotPagerView: View {
    @Environment(AppState.self) private var app
    let shots: [Shot]
    let startID: String
    let session: CameraSession

    @State private var currentID: String?
    @State private var showInfo = true
    @State private var framing: FrameSubject?
    @State private var loadingFrame = false

    var current: Shot? { shots.first { $0.id == (currentID ?? startID) } }

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(shots) { s in
                    ShotPreview(shot: s, session: session)
                        .containerRelativeFrame(.horizontal)
                        .id(s.id)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $currentID)
        .scrollIndicators(.hidden)
        .background(Color.black)
        .onAppear { currentID = startID }
        .onTapGesture { withAnimation(.snappy) { showInfo.toggle() } }
        .safeAreaInset(edge: .bottom) {
            if showInfo, let s = current {
                ShotInfoPanel(shot: s, framing: { await openFrame(s) }, loadingFrame: loadingFrame)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .navigationTitle(current?.primary.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .fullScreenCover(item: $framing) { f in FrameStudioView(image: f.image, meta: f.meta, location: f.location) }
    }

    private func openFrame(_ s: Shot) async {
        loadingFrame = true
        defer { loadingFrame = false }
        if let img = await Previews.full(s, session: session) {
            let loc = s.meta.flatMap { m in m.latitude.flatMap { lat in m.longitude.map { CLLocation(latitude: lat, longitude: $0) } } }
                ?? app.location.location(at: app.correctedDate(for: s))
            framing = FrameSubject(image: img, meta: s.meta, location: loc)
        }
    }
}

struct ShotPreview: View {
    let shot: Shot
    let session: CameraSession
    @State private var image: UIImage?
    @State private var loadMs: Int?

    var body: some View {
        ZStack {
            if let img = image ?? shot.thumbnail {
                Image(uiImage: img).resizable().scaledToFit()
            }
            if image == nil { ProgressView().tint(.white) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topTrailing) {
            if let ms = loadMs, shot.primary.isRemote {
                Text("\(ms) ms").font(.caption2.monospacedDigit()).padding(6)
                    .background(.black.opacity(0.4), in: Capsule()).padding(8).foregroundStyle(.secondary)
            }
        }
        .task(id: shot.id) {
            let t0 = Date()
            image = await Previews.large(shot, session: session)
            loadMs = Int(Date().timeIntervalSince(t0) * 1000)
        }
    }
}

struct ShotInfoPanel: View {
    @Environment(AppState.self) private var app
    let shot: Shot
    let framing: () async -> Void
    let loadingFrame: Bool

    var body: some View {
        let m = shot.meta
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(m?.cameraName ?? "—").font(.headline)
                    if let lens = m?.lens { Text(lens).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
                HStack(spacing: 2) {
                    ForEach(1...5, id: \.self) { n in
                        Image(systemName: n <= shot.rating ? "star.fill" : "star").font(.caption)
                            .foregroundStyle(n <= shot.rating ? Theme.star : .secondary.opacity(0.4))
                    }
                    if shot.isLocked { Image(systemName: "lock.fill").font(.caption).foregroundStyle(Theme.lock).padding(.leading, 4) }
                }
            }

            if let m, !m.exposureLine.isEmpty {
                HStack(spacing: 14) {
                    ForEach([m.focalText, m.apertureText, m.shutterText, m.isoText, m.biasText].compactMap { $0 }, id: \.self) {
                        Text($0).font(.subheadline.monospacedDigit().weight(.medium))
                    }
                }
            }

            if let r = m?.recipe {
                RecipeChips(recipe: r)
            } else if let look = m?.look {
                Label(look, systemImage: "camera.filters").font(.caption).foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                Label(locationText, systemImage: m?.hasGPS == true ? "location.fill" : "location")
                Spacer()
                Text("\(shot.formatBadge) · \(sizeText)")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                Button {
                    app.importShots([shot])
                } label: {
                    Label(shot.wasImported ? "Imported" : importLabel, systemImage: shot.wasImported ? "checkmark" : "square.and.arrow.down")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(shot.wasImported || shot.importState == .queued)

                Button { Task { await framing() } } label: {
                    Group {
                        if loadingFrame { ProgressView() } else { Label("Frame", systemImage: "rectangle.portrait.inset.filled") }
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(shot.isVideo)
            }
            .controlSize(.large)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(.horizontal, 10)
    }

    private var importLabel: String {
        switch shot.importState {
        case .queued: return "Queued"
        case .importing: return "Importing…"
        case .failed: return "Retry import"
        default: return shot.raw != nil && app.settings.includeRAW ? "Import JPG + RAW" : "Import"
        }
    }

    private var sizeText: String {
        if case .http = shot.primary.location { return "Wi-Fi" }
        return ByteCountFormatter.string(fromByteCount: shot.totalBytes, countStyle: .file)
    }

    private var locationText: String {
        if shot.meta?.hasGPS == true { return "GPS in file" }
        if app.location.location(at: app.correctedDate(for: shot)) != nil { return "Will geotag from your walk" }
        return app.settings.borrowPhoneLocations ? "Will borrow iPhone photo location" : "No location"
    }
}

struct RecipeChips: View {
    let recipe: FilmRecipe
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(recipe.filmSimulation, systemImage: "film").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.accent)
            FlowLayout(spacing: 6) {
                ForEach(recipe.rows.dropFirst(), id: \.0) { k, v in
                    Text(k == "Dynamic Range" ? v : "\(abbrev(k)) \(v)")
                        .font(.caption2.weight(.medium))
                        .padding(.horizontal, 7).padding(.vertical, 4)
                        .background(.white.opacity(0.08), in: Capsule())
                }
            }
        }
    }

    private func abbrev(_ k: String) -> String {
        switch k {
        case "Grain Effect": return "Grain"
        case "Color Chrome Effect": return "CCE"
        case "Color Chrome FX Blue": return "CCB"
        case "White Balance": return "WB"
        case "Dynamic Range": return "DR"
        case "High ISO NR": return "NR"
        case "Monochromatic Color": return "Mono"
        default: return k
        }
    }
}

/// Simple wrapping HStack.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > width, x > 0 { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing
            rowH = max(rowH, s.height)
            maxX = max(maxX, x)
        }
        return CGSize(width: min(maxX, width), height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowH = max(rowH, s.height)
        }
    }
}
