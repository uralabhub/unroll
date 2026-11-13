import SwiftUI
import CoreLocation
import MapKit

/// Builds the "Photo Walk Recap" story card from a set of shots.
struct RecapBuilderView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss
    let shots: [Shot]

    @State private var chosen: [String] = []
    @State private var title = ""
    @State private var rendered: UIImage?
    @State private var busy = false
    @State private var shareItem: ShareImage?

    private var pool: [Shot] { shots.filter { !$0.isVideo } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let rendered {
                        Image(uiImage: rendered).resizable().scaledToFit()
                            .frame(maxHeight: 460)
                            .frame(maxWidth: .infinity)
                    } else {
                        Rectangle().fill(Mono.raised).frame(height: 300)
                            .overlay { if busy { ProgressView() } else { Text("Pick up to 6 photos").foregroundStyle(Mono.dim) } }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        CapsLabel("Title")
                        TextField("Title", text: $title)
                            .font(.sans(.title3, .semibold))
                            .padding(.vertical, 8)
                            .overlay(alignment: .bottom) { Hairline() }
                            .onSubmit { Task { await render() } }
                    }
                    HStack {
                        CapsLabel("Photos")
                        Spacer()
                        Text("\(chosen.count) / 6").font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                    }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 1), count: 4), spacing: 1) {
                        ForEach(pool) { s in
                            Mono.raised.aspectRatio(1, contentMode: .fit)
                                .overlay { if let t = s.thumbnail { Image(uiImage: t).resizable().scaledToFill() } }
                                .clipped()
                                .overlay(alignment: .topTrailing) {
                                    if let i = chosen.firstIndex(of: s.id) {
                                        Text("\(i + 1)").font(Mono.data(.caption, weight: .semibold)).foregroundStyle(.black)
                                            .frame(width: 22, height: 22).background(.white)
                                    }
                                }
                                .overlay {
                                    if chosen.contains(s.id) { Rectangle().strokeBorder(.white, lineWidth: 2) }
                                }
                                .onTapGesture { toggle(s) }
                                .task { await Previews.thumbnail(s) }
                        }
                    }
                }
                .padding(16)
            }
            .background(Mono.bg)
            .navigationTitle("Recap card")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button { if let r = rendered { shareItem = ShareImage(image: r) } } label: { Ph("square.and.arrow.up", size: 21) }
                        .disabled(rendered == nil)
                    Button("Save") { Task { await save() } }.bold().disabled(rendered == nil)
                }
            }
            .sheet(item: $shareItem) { ActivityView(items: [$0.image]) }
        }
        .task {
            chosen = Array(pool.sorted { $0.rating > $1.rating }.prefix(6).map(\.id))
            title = defaultTitle()
            await render()
        }
    }

    private func toggle(_ s: Shot) {
        if let i = chosen.firstIndex(of: s.id) { chosen.remove(at: i) } else if chosen.count < 6 { chosen.append(s.id) }
        Haptics.tap()
        Task { await render() }
    }

    private func defaultTitle() -> String {
        let d = pool.map(\.captureDate).min() ?? Date()
        return d.formatted(.dateTime.weekday(.wide)) + " walk"
    }

    private func render() async {
        busy = true
        defer { busy = false }
        let picks = chosen.compactMap { id in pool.first { $0.id == id } }
        var images: [UIImage] = []
        for s in picks {
            if let img = await Previews.large(s, session: app.session) { images.append(img) }
        }
        let dates = pool.map { app.correctedDate(for: $0) }
        let walk = app.location.walk(covering: dates)
        let route = walk?.points.map(\.coordinate) ?? []
        let map = await RecapRenderer.mapSnapshot(route: route, size: CGSize(width: 936, height: 330))
        var subtitle = (dates.min() ?? Date()).formatted(date: .long, time: .omitted)
        if let first = route.first,
           let pm = try? await CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: first.latitude, longitude: first.longitude)).first {
            var parts: [String] = []
            for p in [pm.subLocality, pm.locality, pm.administrativeArea].compactMap({ $0 }) where !parts.contains(p) && parts.count < 2 {
                parts.append(p)
            }
            if !parts.isEmpty { subtitle = parts.joined(separator: ", ") + " · " + subtitle }
        }
        let looks = Array(Set(pool.compactMap { $0.meta?.recipe?.filmSimulation ?? $0.meta?.look })).sorted()
        let cams = Array(Set(pool.compactMap { $0.meta?.cameraName })).sorted()
        let input = RecapInput(
            title: title.isEmpty ? defaultTitle() : title,
            subtitle: subtitle,
            photos: images,
            shotCount: pool.count,
            keeperCount: pool.filter(\.isKeeper).count,
            distanceKm: walk.map { $0.distanceMeters / 1000 },
            duration: walk?.duration,
            cameras: cams,
            looks: looks,
            route: route,
            watermark: !app.pro.isPro
        )
        rendered = await Task.detached(priority: .userInitiated) { RecapRenderer.render(input, map: map) }.value
    }

    private func save() async {
        guard let r = rendered, let data = r.jpegData(compressionQuality: 0.93), await PhotoLibrary.authorize() else { return }
        try? await PhotoLibrary.save(image: data, location: nil)
        app.toast = "Recap saved to Photos"
        Haptics.success()
        dismiss()
    }
}

/// List of recorded photo walks.
struct WalksView: View {
    @Environment(AppState.self) private var app

    var body: some View {
        List {
            if app.location.walks.isEmpty {
                QuietMessage(title: "No walks yet",
                             message: "Start a photo walk before you head out. Imports get their location from it automatically.")
                    .listRowInsets(EdgeInsets())
            }
            ForEach(app.location.walks.reversed(), id: \.id) { (w: PhotoWalk) in
                VStack(alignment: .leading, spacing: 8) {
                    if w.points.count > 1 {
                        Map(interactionModes: []) {
                            MapPolyline(coordinates: w.points.map(\.coordinate)).stroke(.black, lineWidth: 3)
                        }
                        .mapStyle(.standard(pointsOfInterest: .excludingAll))
                        .saturation(0)
                        .frame(height: 140)
                        .allowsHitTesting(false)
                    }
                    HStack {
                        Text(w.start.formatted(date: .abbreviated, time: .shortened)).font(.sans(.body, .medium))
                        if w.isDemo { CapsLabel("Demo") }
                        Spacer()
                        Text(w.end == nil ? "Recording" : String(format: "%.2f km", w.distanceMeters / 1000))
                            .font(Mono.data(.subheadline)).foregroundStyle(Mono.dim)
                    }
                    Text("\(Duration.seconds(w.duration).formatted(.time(pattern: .hourMinute)))  ·  \(w.points.count) GPS fixes")
                        .font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                }
                .swipeActions { Button("Delete", role: .destructive) { app.location.delete(w) } }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Walks")
    }
}
