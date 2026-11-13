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
        .onTapGesture { withAnimation(Motion.glide) { showInfo.toggle() } }
        .safeAreaInset(edge: .bottom) {
            if showInfo, let s = current {
                ShotInfoPanel(shot: s, framing: { await openFrame(s) }, loadingFrame: loadingFrame)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .navigationTitle(current?.primary.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .environment(\.colorScheme, .dark)
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
                Text("\(ms) ms").font(Mono.data(.caption2)).padding(.horizontal, 6).padding(.vertical, 3)
                    .background(.black.opacity(0.6)).padding(10).foregroundStyle(.white.opacity(0.7))
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
    @State private var showRecipe = false

    var body: some View {
        let m = shot.meta
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(m?.cameraName ?? "Unknown camera").font(Mono.sans(.headline))
                    if let lens = m?.lens { Text(lens).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim) }
                }
                Spacer()
                HStack(spacing: 8) {
                    if shot.rating > 0 { StarRow(rating: shot.rating) }
                    if shot.isLocked { Icon("lock-simple-fill", size: 12) }
                }
            }

            if let m, !m.exposureLine.isEmpty {
                HStack(spacing: 0) {
                    ForEach([m.focalText, m.apertureText, m.shutterText, m.isoText, m.biasText].compactMap { $0 }, id: \.self) {
                        Text($0).font(Mono.data(.subheadline, weight: .medium))
                            .lineLimit(1).minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.vertical, 10)
                .overlay(alignment: .top) { Hairline() }
                .overlay(alignment: .bottom) { Hairline() }
            }

            if let r = m?.recipe {
                RecipeSummary(recipe: r, expanded: $showRecipe)
            } else if let look = m?.look {
                DataRow(key: "Look", value: look)
            }

            HStack(spacing: 8) {
                Text(locationText)
                Spacer()
                Text("\(shot.formatBadge)  \(sizeText)")
            }
            .font(Mono.data(.caption2))
            .foregroundStyle(Mono.dim)

            HStack(spacing: 10) {
                Button {
                    app.importShots([shot])
                } label: {
                    Text(shot.wasImported ? "In Photos" : importLabel)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(shot.wasImported || shot.importState == .queued)

                Button { Task { await framing() } } label: {
                    if loadingFrame { ProgressView() } else { Text("Frame") }
                }
                .buttonStyle(SecondaryButtonStyle())
                .frame(width: 110)
                .disabled(shot.isVideo)
            }
        }
        .padding(.horizontal, Mono.gutter)
        .padding(.top, 16)
        .padding(.bottom, 8)
        .background(Color.black.opacity(0.92).ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Hairline() }
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
        if app.location.location(at: app.correctedDate(for: shot)) != nil { return "Location from your walk" }
        return app.settings.borrowPhoneLocations ? "Location from iPhone photos" : "No location"
    }
}

/// Film simulation on one line; tap to see every setting.
struct RecipeSummary: View {
    let recipe: FilmRecipe
    @Binding var expanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(Motion.glide) { expanded.toggle() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        CapsLabel("Recipe")
                        Text(recipe.filmSimulation).font(Mono.sans(.subheadline, weight: .semibold))
                    }
                    Spacer()
                    Text(expanded ? "Hide" : "\(recipe.rows.count - 1) settings")
                        .font(Mono.data(.caption2)).foregroundStyle(Mono.dim)
                    Icon("caret-down", size: 14)
                        .rotationEffect(.degrees(expanded ? 180 : 0)).foregroundStyle(Mono.dim)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                VStack(spacing: 0) {
                    ForEach(Array(recipe.rows.dropFirst().enumerated()), id: \.element.0) { i, row in
                        DataRow(key: row.0, value: row.1).rise(i, step: 0.03, distance: 8)
                    }
                }
                .padding(.top, 6)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
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
