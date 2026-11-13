import SwiftUI
import StoreKit

// MARK: - Recipes

struct RecipesView: View {
    @Environment(AppState.self) private var app

    var body: some View {
        List {
            if app.recipes.items.isEmpty {
                ContentUnavailableView("No recipes yet", systemImage: "camera.filters",
                                       description: Text("Import Fujifilm shots and every film-simulation recipe you used is collected here — ready to share or recreate."))
            }
            ForEach(app.recipes.sorted) { item in
                NavigationLink { RecipeDetailView(item: item) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(item.title).font(.headline)
                            Spacer()
                            Text("\(item.count)×").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        Text(item.recipe.shortLine).font(.caption).foregroundStyle(Theme.accent)
                        if let c = item.camera { Text(c).font(.caption2).foregroundStyle(.secondary) }
                    }
                }
                .swipeActions { Button("Delete", role: .destructive) { app.recipes.delete(item.id) } }
            }
        }
        .navigationTitle("Recipes")
    }
}

struct RecipeDetailView: View {
    @Environment(AppState.self) private var app
    let item: RecipeBook.Item
    @State private var name = ""
    @State private var shareItem: ShareImage?

    var body: some View {
        List {
            Section("Name") {
                TextField(item.recipe.filmSimulation, text: $name)
                    .onSubmit { app.recipes.rename(item.id, to: name) }
            }
            Section("Settings") {
                ForEach(item.recipe.rows, id: \.0) { k, v in
                    LabeledContent(k, value: v)
                }
            }
            Section {
                Button { shareItem = ShareImage(image: card()) } label: { Label("Share recipe card", systemImage: "square.and.arrow.up") }
                Button {
                    UIPasteboard.general.string = ([name.isEmpty ? item.title : name] + item.recipe.rows.map { "\($0.0): \($0.1)" }).joined(separator: "\n")
                    app.toast = "Recipe copied"
                } label: { Label("Copy as text", systemImage: "doc.on.doc") }
            }
        }
        .navigationTitle(item.title)
        .onAppear { name = item.name ?? "" }
        .onDisappear { app.recipes.rename(item.id, to: name) }
        .sheet(item: $shareItem) { ActivityView(items: [$0.image]) }
    }

    private func card() -> UIImage {
        let rows = item.recipe.rows
        let size = CGSize(width: 1080, height: 360 + CGFloat(rows.count) * 76)
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            UIColor(white: 0.07, alpha: 1).setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            let amber = UIColor(red: 1, green: 0.66, blue: 0.25, alpha: 1)
            FrameRenderer.draw("FILM RECIPE", at: CGPoint(x: 80, y: 100), size: 30, weight: .bold, color: amber, align: .left, kern: 6)
            FrameRenderer.draw(name.isEmpty ? item.title : name, at: CGPoint(x: 80, y: 170), size: 64, weight: .bold, color: .white, align: .left)
            FrameRenderer.draw(item.camera ?? "", at: CGPoint(x: 80, y: 235), size: 30, weight: .regular, color: UIColor(white: 1, alpha: 0.5), align: .left)
            var y: CGFloat = 320
            for (k, v) in rows {
                FrameRenderer.draw(k.uppercased(), at: CGPoint(x: 80, y: y), size: 26, weight: .semibold, color: UIColor(white: 1, alpha: 0.5), align: .left, kern: 2)
                FrameRenderer.draw(v, at: CGPoint(x: 1000, y: y), size: 32, weight: .semibold, color: .white, align: .right)
                y += 76
            }
            if !app.pro.isPro {
                FrameRenderer.draw("made with Unroll", at: CGPoint(x: 1000, y: size.height - 50), size: 24, weight: .medium, color: UIColor(white: 1, alpha: 0.4), align: .right)
            }
        }
    }
}

// MARK: - Clock sync

struct ClockSyncView: View {
    @Environment(AppState.self) private var app
    @State private var result: String?
    @State private var reading = false

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Text("Camera clocks drift minutes per month and ignore time zones — which puts your photos in the wrong place. Fix it once:")
                    .font(.subheadline).foregroundStyle(.secondary)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    VStack(spacing: 10) {
                        if let qr = ClockSync.qr(for: ctx.date, size: 520) {
                            Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                                .frame(maxWidth: 260)
                                .padding(16).background(.white, in: RoundedRectangle(cornerRadius: 16))
                        }
                        Text(ctx.date.formatted(.dateTime.hour().minute().second()))
                            .font(.system(size: 44, weight: .bold, design: .rounded).monospacedDigit())
                    }
                }
                VStack(alignment: .leading, spacing: 10) {
                    step(1, "Turn your screen brightness up and take a photo of this code with your camera.")
                    step(2, "Connect the camera (cable or card).")
                    step(3, "Tap “Read from camera”. Unroll compares the code with the photo’s timestamp.")
                }
                Button {
                    Task { await read() }
                } label: {
                    Group { if reading { ProgressView() } else { Label("Read from camera", systemImage: "qrcode.viewfinder") } }
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(app.session == nil || reading)
                if app.session == nil { Text("Connect a camera first.").font(.caption).foregroundStyle(.secondary) }
                if let result { Text(result).font(.callout.weight(.semibold)).multilineTextAlignment(.center).foregroundStyle(Theme.accent) }

                if !app.settings.clockOffsets.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Corrections in use").font(.headline)
                        ForEach(app.settings.clockOffsets.sorted(by: { $0.key < $1.key }), id: \.key) { model, off in
                            HStack {
                                Text(model)
                                Spacer()
                                Text(ClockSyncView.describe(off)).foregroundStyle(.secondary)
                                Button { app.settings.clockOffsets[model] = nil } label: { Image(systemName: "xmark.circle.fill") }
                                    .buttonStyle(.plain).foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                        }
                    }
                    .padding(14)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding(16)
        }
        .navigationTitle("Clock Sync")
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.caption.bold()).foregroundStyle(.black).frame(width: 22, height: 22).background(Theme.accent, in: Circle())
            Text(text).font(.subheadline)
        }
    }

    static func describe(_ off: Double) -> String {
        let s = Int(abs(off).rounded())
        if s < 2 { return "in sync" }
        let txt = s >= 60 ? "\(s / 60)m \(s % 60)s" : "\(s)s"
        return off > 0 ? "\(txt) fast" : "\(txt) slow"
    }

    private func read() async {
        guard let session = app.session else { return }
        reading = true
        defer { reading = false }
        // Look at the newest few shots for the code.
        for shot in session.shots.prefix(5) where !shot.isVideo {
            if shot.meta == nil { await session.loadMeta(shot) }
            guard let img = await Previews.large(shot, session: session),
                  let truth = await ClockSync.readTimestamp(in: img),
                  let exif = shot.meta?.captureDate() else { continue }
            let off = ClockSync.offset(exif: exif, truth: truth)
            let model = shot.meta?.model ?? session.name
            app.settings.clockOffsets[model] = off
            result = "\(shot.meta?.cameraName ?? model) is \(ClockSyncView.describe(off)). Corrected for every import from now on."
            Haptics.success()
            return
        }
        result = "Couldn’t find the code in the latest 5 shots. Take a sharp, well-lit photo of it and try again."
    }
}

// MARK: - Settings

struct SettingsView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var s = app.settings
        @Bindable var pro = app.pro
        Form {
            Section {
                Picker("When a camera connects", selection: $s.autoImport) {
                    ForEach(AutoImportRule.allCases) { Text($0.label).tag($0) }
                }
                Toggle("Include RAW twin (JPG+RAW as one photo)", isOn: $s.includeRAW)
                Picker("Save to", selection: $s.albumMode) {
                    ForEach(AlbumMode.allCases) { Text($0.label).tag($0) }
                }
                Toggle("Eject camera when done", isOn: $s.autoEject)
            } header: { Text("Import") } footer: {
                Text("“★ & 🔒 shots” pulls only what you rated or protected on the camera body — Pro.")
            }

            Section {
                Toggle("Add location to imports", isOn: $s.geotag)
                Toggle("Borrow location from iPhone photos", isOn: $s.borrowPhoneLocations)
                NavigationLink("Camera clock corrections") { ClockSyncView() }
            } header: { Text("Location") } footer: {
                Text("Uses your Photo Walk trail first, then iPhone photos taken within 20 minutes. Originals are never modified — location is attached in Photos.")
            }

            Section("Unroll Pro") {
                if app.pro.isPro {
                    Label("Pro unlocked — thank you!", systemImage: "checkmark.seal.fill").foregroundStyle(Theme.accent)
                } else {
                    Button("See Pro") { dismiss(); app.showPaywall = true }
                    LabeledContent("Free imports left today", value: "\(app.quota.remaining)")
                }
                Button("Restore purchases") { Task { await app.pro.restore() } }
            }

            Section("Developer") {
                Toggle("Unlock Pro (testing)", isOn: $pro.debugUnlock)
                Button("Forget import history", role: .destructive) { app.ledger.reset() }
            }

            Section {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
            } footer: {
                Text("No account, no analytics, no cloud. Everything stays on your phone.")
            }
        }
        .navigationTitle("Settings")
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
}

// MARK: - Paywall

struct PaywallView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss

    private let perks: [(String, String, String)] = [
        ("infinity", "Unlimited imports", "Free plan: \(Quota.freePerDay) a day"),
        ("star.circle", "Auto-pull ★ & 🔒 shots", "Plug in → your keepers land in Photos, hands-free"),
        ("location.circle", "Auto-geotag every import", "From your walk or iPhone photos, clock-corrected"),
        ("rectangle.portrait.on.rectangle.portrait", "All frame styles", "Recipe card, Instant, Film strip — full resolution"),
        ("eye.slash", "No watermark", "On frames, recaps and recipe cards"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    VStack(spacing: 8) {
                        Image(systemName: "bolt.circle.fill").font(.system(size: 56)).foregroundStyle(Theme.accent)
                        Text("Unroll Pro").font(.largeTitle.bold())
                        Text("From camera to posted in seconds.").foregroundStyle(.secondary)
                    }
                    .padding(.top, 10)

                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(perks, id: \.1) { icon, title, sub in
                            HStack(spacing: 14) {
                                Image(systemName: icon).font(.title2).foregroundStyle(Theme.accent).frame(width: 32)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(title).font(.headline)
                                    Text(sub).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .padding(18)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))

                    VStack(spacing: 10) {
                        planButton(app.pro.lifetime, title: "Founder Lifetime", fallbackPrice: "$29.99", note: "Pay once. Yours forever.", highlight: true)
                        planButton(app.pro.yearly, title: "Yearly", fallbackPrice: "$14.99 / year", note: "7-day free trial", highlight: false)
                    }

                    if let m = app.pro.message { Text(m).font(.footnote).foregroundStyle(.secondary) }
                    Button("Restore purchases") { Task { await app.pro.restore() } }.font(.footnote)
                }
                .padding(16)
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Not now") { dismiss() } } }
        }
        .onChange(of: app.pro.isPro) { _, isPro in if isPro { dismiss() } }
        .presentationDragIndicator(.visible)
    }

    private func planButton(_ product: Product?, title: String, fallbackPrice: String, note: String, highlight: Bool) -> some View {
        Button {
            guard let product else { app.pro.message = "Store unavailable right now — try again shortly."; return }
            Task { await app.pro.buy(product) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(note).font(.caption).opacity(0.75)
                }
                Spacer()
                Text(product?.displayPrice ?? fallbackPrice).font(.headline.monospacedDigit())
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(highlight ? Theme.accent : Theme.card, in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(highlight ? .black : .primary)
        }
        .buttonStyle(.plain)
        .disabled(app.pro.purchasing)
    }
}

// MARK: - Import HUD + toast

struct ImportHUD: View {
    @Environment(AppState.self) private var app
    @State private var showDone = false

    var body: some View {
        let imp = app.importer
        Group {
            if imp.isRunning {
                HStack(spacing: 12) {
                    ProgressView(value: imp.progress).progressViewStyle(.circular).tint(Theme.accent)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Importing \(min(imp.completedInRun + 1, imp.totalInRun)) of \(imp.totalInRun)").font(.subheadline.weight(.semibold))
                        Text(imp.throughputMBps.map { String(format: "%.0f MB/s", $0) } ?? (imp.current?.primary.name ?? ""))
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { imp.cancelAll() } label: { Image(systemName: "xmark.circle.fill").font(.title3) }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                }
            } else if showDone, let run = imp.lastFinishedRun {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(run.count) in Photos · \(String(format: "%.1f", run.seconds))s").font(.subheadline.weight(.semibold))
                        Text(run.geotagged > 0 ? "\(run.geotagged) geotagged" : "Originals saved untouched").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Open Photos") {
                        if let u = URL(string: "photos-redirect://") { UIApplication.shared.open(u) }
                    }
                    .font(.subheadline.weight(.semibold))
                }
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 12)
        .opacity(imp.isRunning || showDone ? 1 : 0)
        .offset(y: imp.isRunning || showDone ? -70 : 120)
        .animation(.spring(duration: 0.4), value: imp.isRunning)
        .animation(.spring(duration: 0.4), value: showDone)
        .onChange(of: imp.isRunning) { _, running in
            if !running, imp.lastFinishedRun != nil {
                showDone = true
                Haptics.success()
                Task { try? await Task.sleep(for: .seconds(5)); showDone = false }
            }
        }
        .allowsHitTesting(imp.isRunning || showDone)
    }
}

struct ToastView: View {
    @Environment(AppState.self) private var app
    var body: some View {
        Group {
            if let t = app.toast ?? app.importer.lastError {
                Text(t)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, 6)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: t) {
                        try? await Task.sleep(for: .seconds(3))
                        app.toast = nil
                        app.importer.lastError = nil
                    }
            }
        }
        .animation(.spring(duration: 0.35), value: app.toast)
    }
}
