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
            VStack(spacing: 0) {
                ZStack {
                    if let preview {
                        Image(uiImage: preview).resizable().scaledToFit()
                    } else {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)

                Hairline()
                VStack(alignment: .leading, spacing: 16) {
                    styleStrip
                    controls
                }
                .padding(.top, 14)
            }
            .padding(.bottom, 8)
            .background(Color(white: 0.07))
            .navigationTitle("Frame")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button { Task { await share() } } label: { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Share")
                    Button { Task { await save() } } label: {
                        if saving { ProgressView() } else { Text("Save").bold() }
                    }
                }
            }
            .sheet(item: $shareItem) { item in ActivityView(items: [item.image]) }
        }
        .environment(\.colorScheme, .dark)
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
            HStack(spacing: 22) {
                ForEach(FrameStyle.allCases) { s in
                    let on = options.style == s
                    let locked = s.isPro && !app.pro.isPro
                    Button {
                        if locked { app.showPaywall = true; return }
                        withAnimation(.snappy(duration: 0.2)) { options.style = s }
                        Haptics.tap()
                    } label: {
                        VStack(spacing: 7) {
                            HStack(spacing: 4) {
                                Text(s.label).font(.sans(.subheadline, on ? .semibold : .regular))
                                if locked { Image(systemName: "lock.fill").font(.system(size: 9)) }
                            }
                            .foregroundStyle(on ? Mono.ink : Mono.dim)
                            Rectangle().fill(on ? Mono.ink : .clear).frame(height: 1.5)
                        }
                        .fixedSize()
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Mono.gutter)
        }
        .scrollIndicators(.hidden)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 0) {
                CapsLabel("Ratio").frame(width: 64, alignment: .leading)
                ForEach(FrameAspect.allCases) { a in
                    Button { options.aspect = a; Haptics.tap() } label: {
                        Text(a.label)
                            .font(Mono.data(.footnote, weight: options.aspect == a ? .semibold : .regular))
                            .foregroundStyle(options.aspect == a ? Mono.ink : Mono.dim)
                            .frame(maxWidth: .infinity, minHeight: 32)
                            .overlay(alignment: .bottom) {
                                if options.aspect == a { Rectangle().fill(Mono.ink).frame(height: 1.5) }
                            }
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Mono.gutter)
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    Toggle("Dark", isOn: $options.dark).toggleStyle(ChipToggle())
                    Toggle("Exposure", isOn: $options.showExposure).toggleStyle(ChipToggle())
                    Toggle(meta?.recipe != nil ? "Recipe" : "Look", isOn: $options.showRecipe).toggleStyle(ChipToggle())
                    Toggle("Date", isOn: $options.showDate).toggleStyle(ChipToggle())
                    if place != nil { Toggle("Place", isOn: $options.showPlace).toggleStyle(ChipToggle()) }
                }
                .padding(.horizontal, Mono.gutter)
            }
            .scrollIndicators(.hidden)
        }
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

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
