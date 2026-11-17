import SwiftUI
import CoreLocation

struct ShotPagerView: View {
    @Environment(AppState.self) private var app
    let shots: [Shot]
    let startID: String
    let session: CameraSession

    @State private var currentID: String?
    @State private var showInfo = false
    @State private var chrome = true
    @State private var framing: FrameSubject?
    @State private var loadingFrame = false

    var current: Shot? { shots.first { $0.id == (currentID ?? startID) } }
    private var index: Int? { shots.firstIndex { $0.id == (currentID ?? startID) } }

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
        .background(Color.black.ignoresSafeArea())
        .onAppear { currentID = startID }
        .onTapGesture { withAnimation(Motion.glide) { chrome.toggle(); if !chrome { showInfo = false } } }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if chrome, let s = current {
                VStack(spacing: 0) {
                    if showInfo {
                        ShotInfoPanel(shot: s)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    actions(s)
                }
                .background(.ultraThinMaterial)
                .transition(.opacity)
            }
        }
        .toolbar(chrome ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(current?.captureDate.formatted(date: .abbreviated, time: .shortened) ?? "")
                        .font(Mono.sans(.subheadline, weight: .semibold))
                    if let i = index {
                        Text("\(i + 1) of \(shots.count)").font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                    }
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .statusBarHidden(!chrome)
        .environment(\.colorScheme, .dark)
        .fullScreenCover(item: $framing) { f in FrameStudioView(image: f.image, meta: f.meta, location: f.location) }
    }

    private func actions(_ s: Shot) -> some View {
        HStack(spacing: 0) {
            barButton(loadingFrame ? nil : "square.dashed", "Frame") { Task { await openFrame(s) } }
                .disabled(s.isVideo || loadingFrame)
            Spacer()
            barButton(showInfo ? "info.circle.fill" : "info.circle", "Info") {
                withAnimation(Motion.glide) { showInfo.toggle() }
            }
            Spacer()
            importButton(s)
        }
        .padding(.horizontal, 28)
        .frame(height: 52)
    }

    private func barButton(_ symbol: String?, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let symbol { Image(systemName: symbol) } else { ProgressView() }
            }
            .font(.system(size: 21))
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }

    @ViewBuilder private func importButton(_ s: Shot) -> some View {
        switch s.importState {
        case .importing(let f):
            ZStack {
                Circle().stroke(Mono.tile, lineWidth: 3)
                Circle().trim(from: 0, to: f).stroke(Mono.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90)).animation(Motion.progress, value: f)
            }
            .frame(width: 24, height: 24).frame(width: 44, height: 44)
            .accessibilityLabel("Importing")
        case .queued:
            Image(systemName: "clock").font(.system(size: 21)).foregroundStyle(Mono.dim).frame(width: 44, height: 44)
        default:
            if s.wasImported {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 21)).foregroundStyle(Mono.dim)
                    .frame(width: 44, height: 44).accessibilityLabel("In Photos")
            } else {
                barButton(s.importState.isFailed ? "arrow.clockwise" : "square.and.arrow.down", "Import") { app.importShots([s]) }
            }
        }
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

extension ImportState {
    var isFailed: Bool { if case .failed = self { return true }; return false }
}

struct ShotPreview: View {
    let shot: Shot
    let session: CameraSession
    @State private var image: UIImage?
    @State private var thumb: UIImage?
    @State private var loading = false

    var body: some View {
        ZStack {
            if let img = image ?? thumb {
                Image(uiImage: img).resizable().scaledToFit()
                    .transition(.opacity)
            }
            if image == nil && loading {
                ProgressView().tint(.white).transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: shot.id) {
            thumb = shot.thumbnail
            // Only show a spinner if the preview is actually slow.
            let spinner = Task { try? await Task.sleep(for: .milliseconds(350)); if !Task.isCancelled { withAnimation { loading = true } } }
            let img = await Previews.large(shot, session: session)
            spinner.cancel()
            withAnimation(.easeOut(duration: 0.2)) { image = img; loading = false }
        }
    }
}

/// EXIF, recipe, location and file facts. Shown under the photo when Info is on.
struct ShotInfoPanel: View {
    @Environment(AppState.self) private var app
    let shot: Shot
    @State private var showRecipe = false

    var body: some View {
        let m = shot.meta
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(m?.cameraName ?? "Unknown camera").font(Mono.sans(.headline))
                    if let lens = m?.lens { Text(lens).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim) }
                }
                Spacer()
                HStack(spacing: 8) {
                    if shot.rating > 0 { StarRow(rating: shot.rating) }
                    if shot.isLocked { Image(systemName: "lock.fill").font(.system(size: 12)) }
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
                .padding(.vertical, 8)
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
                Text("\(shot.primary.name) · \(shot.formatBadge) · \(sizeText)")
            }
            .font(Mono.data(.caption))
            .foregroundStyle(Mono.dim)
            .lineLimit(1)
        }
        .padding(.horizontal, Mono.gutter)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    private var sizeText: String {
        if case .http = shot.primary.location { return "Wi‑Fi" }
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
                        DataRow(key: row.0, value: row.1)
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
