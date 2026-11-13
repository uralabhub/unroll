import SwiftUI
import CoreLocation

struct FrameStudioView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss
    let image: UIImage
    let meta: ShotMeta?
    var location: CLLocation? = nil

    @State private var options = FrameStudioView.savedOptions
    @State private var preview: UIImage?
    @State private var proxy: UIImage?
    @State private var saving = false
    @State private var shareItem: ShareImage?
    @State private var place: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                ZStack {
                    if let preview {
                        Image(uiImage: preview).resizable().scaledToFit()
                            .shadow(color: .black.opacity(0.5), radius: 20, y: 8)
                    } else {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal, 16)

                styleStrip
                controls
            }
            .padding(.bottom, 8)
            .background(Color(white: 0.04))
            .navigationTitle("Frame")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button { Task { await share() } } label: { Image(systemName: "square.and.arrow.up") }
                    Button { Task { await save() } } label: {
                        if saving { ProgressView() } else { Text("Save").bold() }
                    }
                }
            }
            .sheet(item: $shareItem) { item in ActivityView(items: [item.image]) }
        }
        .task {
            proxy = await Task.detached { ImageDecode.downsample(image.normalized().jpegData(compressionQuality: 0.9) ?? Data(), maxPixel: 1400) }.value ?? image
            if let loc = resolvedLocation {
                place = try? await CLGeocoder().reverseGeocodeLocation(loc).first.flatMap { pm in
                    [pm.subLocality, pm.locality].compactMap { $0 }.first ?? pm.name
                }
            }
            rerender()
        }
        .onChange(of: options) { _, o in
            FrameStudioView.savedOptions = o
            rerender()
        }
    }

    private var styleStrip: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(FrameStyle.allCases) { s in
                    Button {
                        if s.isPro && !app.pro.isPro { app.showPaywall = true; return }
                        options.style = s
                        Haptics.tap()
                    } label: {
                        HStack(spacing: 4) {
                            Text(s.label)
                            if s.isPro && !app.pro.isPro { Image(systemName: "lock.fill").font(.caption2) }
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(options.style == s ? Theme.accent : Theme.card, in: Capsule())
                        .foregroundStyle(options.style == s ? .black : .primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }

    private var controls: some View {
        VStack(spacing: 10) {
            Picker("Aspect", selection: $options.aspect) {
                ForEach(FrameAspect.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            HStack(spacing: 8) {
                Toggle("Dark", isOn: $options.dark).toggleStyle(ChipToggle())
                Toggle("Exposure", isOn: $options.showExposure).toggleStyle(ChipToggle())
                Toggle(meta?.recipe != nil ? "Recipe" : "Look", isOn: $options.showRecipe).toggleStyle(ChipToggle())
                Toggle("Date", isOn: $options.showDate).toggleStyle(ChipToggle())
                if place != nil { Toggle("Place", isOn: $options.showPlace).toggleStyle(ChipToggle()) }
            }
        }
        .padding(.horizontal, 16)
    }

    private var resolvedLocation: CLLocation? {
        if let m = meta, let lat = m.latitude, let lon = m.longitude { return CLLocation(latitude: lat, longitude: lon) }
        return location
    }

    private func current(_ o: FrameOptions) -> FrameOptions {
        var o = o
        o.placeName = place
        o.watermark = !app.pro.isPro
        return o
    }

    private func rerender() {
        guard let proxy else { return }
        let o = current(options)
        let m = meta
        Task {
            preview = await Task.detached(priority: .userInitiated) { FrameRenderer.render(proxy, meta: m, options: o) }.value
        }
    }

    private func renderFull() async -> UIImage {
        let o = current(options), m = meta, img = image
        return await Task.detached(priority: .userInitiated) { FrameRenderer.render(img, meta: m, options: o) }.value
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let full = await renderFull()
        guard let data = full.jpegData(compressionQuality: 0.95), await PhotoLibrary.authorize() else { return }
        do {
            try await PhotoLibrary.save(image: data, location: resolvedLocation)
            app.toast = "Framed photo saved at full resolution"
            Haptics.success()
            dismiss()
        } catch {
            app.toast = error.localizedDescription
        }
    }

    private func share() async {
        shareItem = ShareImage(image: await renderFull())
    }

    static var savedOptions: FrameOptions {
        get {
            (UserDefaults.standard.data(forKey: "frameOptions")).flatMap { try? JSONDecoder().decode(FrameOptions.self, from: $0) } ?? FrameOptions()
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: "frameOptions") }
    }
}

struct ShareImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

struct ChipToggle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle(); Haptics.tap() } label: {
            configuration.label
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 10).padding(.vertical, 7)
                .background(configuration.isOn ? Theme.accent.opacity(0.22) : Theme.card, in: Capsule())
                .foregroundStyle(configuration.isOn ? Theme.accent : .secondary)
        }
        .buttonStyle(.plain)
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
