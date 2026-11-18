import SwiftUI
import StoreKit

// MARK: - Recipes

struct RecipesView: View {
    @Environment(AppState.self) private var app

    var body: some View {
        List {
            if app.recipes.items.isEmpty {
                QuietMessage(title: "No recipes yet",
                             message: "Import Fujifilm shots and every recipe you used is collected here, ready to share or dial back in.")
                    .listRowInsets(EdgeInsets())
            }
            ForEach(Array(app.recipes.sorted.enumerated()), id: \.element.id) { i, item in
                NavigationLink { RecipeDetailView(item: item) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(item.title).font(Mono.sans(.body, weight: .medium))
                            Spacer()
                            Text(item.count == 1 ? "1 shot" : "\(item.count) shots").font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                        }
                        Text(item.recipe.shortLine.replacingOccurrences(of: item.recipe.filmSimulation + " · ", with: "")).font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                        if let c = item.camera { Text(c.uppercased()).font(Mono.caps(10)).tracking(1).foregroundStyle(Mono.faint) }
                    }
                    .padding(.vertical, 4)
                }
                .rise(i)
                .swipeActions { Button("Delete", role: .destructive) { app.recipes.delete(item.id) } }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Film recipes")
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
                    LabeledContent(k) { Text(v).font(Mono.data(.subheadline)) }
                }
            }
            Section {
                Button("Share recipe card") { shareItem = ShareImage(image: card()) }
                Button {
                    UIPasteboard.general.string = ([name.isEmpty ? item.title : name] + item.recipe.rows.map { "\($0.0): \($0.1)" }).joined(separator: "\n")
                    app.toast = "Recipe copied"
                } label: { Text("Copy as text") }
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
            UIColor.black.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            let mono = UIFont.monospacedSystemFont(ofSize: 26, weight: .medium)
            NSAttributedString(string: "FILM RECIPE", attributes: [.font: mono, .foregroundColor: UIColor(white: 1, alpha: 0.5), .kern: 5])
                .draw(at: CGPoint(x: 80, y: 84))
            FrameRenderer.draw(name.isEmpty ? item.title : name, at: CGPoint(x: 80, y: 170), size: 64, weight: .bold, color: .white, align: .left)
            FrameRenderer.draw(item.camera ?? "", at: CGPoint(x: 80, y: 235), size: 30, weight: .regular, color: UIColor(white: 1, alpha: 0.5), align: .left)
            UIColor(white: 1, alpha: 0.2).setFill(); ctx.fill(CGRect(x: 80, y: 280, width: 920, height: 2))
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
            VStack(alignment: .leading, spacing: 28) {
                Text("Camera clocks drift and ignore time zones, which puts photos in the wrong place on the map. Fix it once per camera.")
                    .font(Mono.sans(.subheadline)).foregroundStyle(Mono.dim)
                    .padding(.horizontal, Mono.gutter)

                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    VStack(spacing: 14) {
                        if let qr = ClockSync.qr(for: ctx.date, size: 520) {
                            Image(uiImage: qr).interpolation(.none).resizable().scaledToFit()
                                .frame(maxWidth: 240)
                                .padding(14).background(.white)
                        }
                        let clock = ctx.date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
                        Text(clock)
                            .font(Mono.sans(size: 40, weight: .semibold).monospacedDigit())
                            .contentTransition(.numericText(countsDown: false))
                            .animation(Motion.snap, value: clock)
                    }
                    .frame(maxWidth: .infinity)
                }
                .rise(1)

                MonoSection("How") {
                    step(1, "Turn the screen brightness up and photograph this code with the camera.")
                    step(2, "Connect the camera by cable or card.")
                    step(3, "Tap Read from camera. Unroll compares the code with the photo’s timestamp.")
                }

                VStack(alignment: .leading, spacing: 10) {
                    Button {
                        Task { await read() }
                    } label: {
                        ZStack {
                            if reading { ProgressView().transition(.popIn) } else { Text("Read from camera").transition(.swapUp) }
                        }
                        .animation(Motion.snap, value: reading)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(app.session == nil || reading)
                    if app.session == nil { Text("Connect a camera first.").font(Mono.sans(.footnote)).foregroundStyle(Mono.dim) }
                    if let result {
                        Text(result).font(Mono.sans(.subheadline, weight: .medium))
                            .id(result)
                            .transition(.swapUp)
                    }
                }
                .animation(Motion.glide, value: result)
                .padding(.horizontal, Mono.gutter)

                if !app.settings.clockOffsets.isEmpty {
                    MonoSection("Corrections in use") {
                        ForEach(app.settings.clockOffsets.sorted(by: { $0.key < $1.key }), id: \.key) { model, off in
                            HStack {
                                Text(model)
                                Spacer()
                                Text(ClockSyncView.describe(off)).font(Mono.data(.subheadline)).foregroundStyle(Mono.dim)
                                Button { app.settings.clockOffsets[model] = nil } label: { Icon("x", size: 16) }
                                    .buttonStyle(.plain).foregroundStyle(Mono.dim).padding(.leading, 8)
                                    .accessibilityLabel("Remove correction")
                            }
                            .padding(.horizontal, Mono.gutter).padding(.vertical, 14)
                            .transition(.move(edge: .leading).combined(with: .opacity))
                        }
                    }
                    .transition(.opacity)
                }
            }
            .padding(.vertical, 16)
            .animation(Motion.glide, value: app.settings.clockOffsets)
        }
        .background(Mono.bg)
        .navigationTitle("Clock sync")
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = app.importer.isRunning }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Text(String(format: "%02d", n)).font(Mono.data(.footnote, weight: .medium)).foregroundStyle(Mono.dim)
            Text(text).font(Mono.sans(.subheadline)).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Mono.gutter).padding(.vertical, 12)
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
                Toggle("Keep RAW with JPEG", isOn: $s.includeRAW)
                Picker("Save to", selection: $s.albumMode) {
                    ForEach(AlbumMode.allCases) { Text($0.label).tag($0) }
                }
                Toggle("Eject camera when done", isOn: $s.autoEject)
            } header: { Text("Import") } footer: {
                Text("Picks are shots you rated or protected on the camera body. Auto-import of picks is part of Pro.")
            }

            Section {
                Toggle("Add location to imports", isOn: $s.geotag)
                Toggle("Borrow location from iPhone photos", isOn: $s.borrowPhoneLocations)
                NavigationLink("Camera clock corrections") { ClockSyncView() }
            } header: { Text("Location") } footer: {
                Text("Uses your Photo Walk trail first, then iPhone photos taken within 20 minutes. Originals are never modified — location is attached in Photos.")
            }

            Section {
                Button {
                    dismiss()   // leave Settings so the library shows once the camera answers
                    Task { await app.connectRicoh() }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Ricoh GR over Wi‑Fi").foregroundStyle(Mono.ink)
                        Text("GR III, GR IIIx: join the camera’s Wi‑Fi in iOS Settings first")
                            .font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                    }
                }
            } header: { Text("Connections") } footer: {
                Text("USB cameras and SD cards work from the home screen. Wi‑Fi transfer is only available for Ricoh GR cameras.")
            }

            Section("Unroll Pro") {
                if app.pro.isPro {
                    Text("Pro is unlocked. Thank you.")
                } else {
                    Button("See Pro") { app.showPaywall = true }
                    LabeledContent("Free imports left today", value: "\(app.quota.remaining)")
                }
                Button("Restore purchases") { Task { await app.pro.restore() } }
            }

            #if DEBUG
            Section("Developer") {
                Toggle("Unlock Pro (testing)", isOn: $pro.debugUnlock)
                Button("Forget import history", role: .destructive) { app.ledger.reset() }
            }
            #endif

            Section {
                Link("Privacy policy", destination: Legal.privacy)
                Link("Terms of use", destination: Legal.terms)
                Link("Support", destination: Legal.support)
            }

            Section {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
            } footer: {
                Text("No account, no analytics, no cloud. Everything stays on your phone.")
            }
        }
        .navigationTitle("Settings")
    }
}

// MARK: - Paywall

struct PaywallView: View {
    @Environment(AppState.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var plan: Plan = .lifetime

    enum Plan { case lifetime, yearly, monthly, weekly }

    private func product(_ p: Plan) -> Product? {
        switch p {
        case .lifetime: app.pro.lifetime
        case .yearly: app.pro.yearly
        case .monthly: app.pro.monthly
        case .weekly: app.pro.weekly
        }
    }

    private var yearlyPrice: String { app.pro.yearly?.displayPrice ?? "$14.99" }
    private var monthlyPrice: String { app.pro.monthly?.displayPrice ?? "$3.99" }
    private var weeklyPrice: String { app.pro.weekly?.displayPrice ?? "$1.99" }

    private var buttonTitle: String {
        switch plan {
        case .lifetime: "Buy lifetime"
        case .yearly: "Start free trial"
        case .monthly, .weekly: "Subscribe"
        }
    }

    private var renewalNote: String? {
        let tail = "Renews automatically until you cancel in Settings › Apple ID › Subscriptions, at least 24 hours before the period ends."
        switch plan {
        case .lifetime: return nil
        case .yearly: return "7 days free, then \(yearlyPrice) a year. " + tail
        case .monthly: return "\(monthlyPrice) a month. " + tail
        case .weekly: return "\(weeklyPrice) a week. " + tail
        }
    }

    private let perks: [(String, String)] = [
        ("Unlimited imports", "Free plan: \(Quota.freePerDay) a day"),
        ("Auto-import picks", "Plug in and your rated or protected shots go straight to Photos"),
        ("Automatic locations", "From your walk or iPhone photos, corrected for clock drift"),
        ("Every frame style", "Recipe card, Instant and Film strip at full resolution"),
        ("No watermark", "On frames, recaps and recipe cards"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    VStack(alignment: .leading, spacing: 8) {
                        CapsLabel("Unroll Pro")
                        Text("Camera to posted, without the busywork.")
                            .font(Mono.sans(size: 28, weight: .semibold)).tracking(-0.6)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, Mono.gutter)
                    .rise(0, distance: 28)

                    MonoSection {
                        ForEach(perks, id: \.0) { title, sub in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(title).font(Mono.sans(.body, weight: .medium))
                                Text(sub).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Mono.gutter).padding(.vertical, 12)
                        }
                    }

                    VStack(spacing: 10) {
                        planRow(.lifetime, title: "Lifetime", price: app.pro.lifetime?.displayPrice ?? "$29.99", note: "Pay once. Founder price.")
                        planRow(.yearly, title: "Yearly", price: yearlyPrice + " / yr", note: "7-day free trial. Best value.")
                        planRow(.monthly, title: "Monthly", price: monthlyPrice + " / mo", note: "Cancel anytime")
                        planRow(.weekly, title: "Weekly", price: weeklyPrice + " / wk", note: "For a single trip")
                    }
                    .padding(.horizontal, Mono.gutter)
                    .rise(7, distance: 28)

                }
                .padding(.vertical, 12)
            }
            .background(Mono.bg)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 0) {
                    Hairline()
                    VStack(spacing: 12) {
                        Button {
                            guard let product = self.product(plan) else { app.pro.message = "The App Store isn’t reachable right now. Try again shortly."; return }
                            Task { await app.pro.buy(product) }
                        } label: {
                            ZStack {
                                if app.pro.purchasing {
                                    ProgressView().transition(.popIn)
                                } else {
                                    Text(buttonTitle)
                                        .id(plan)
                                        .transition(.swapUp)
                                }
                            }
                            .animation(Motion.snap, value: plan)
                            .animation(Motion.snap, value: app.pro.purchasing)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(app.pro.purchasing)

                        if let m = app.pro.message { Text(m).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim) }
                        if let note = renewalNote {
                            Text(note)
                                .font(Mono.sans(.caption2)).foregroundStyle(Mono.dim)
                                .multilineTextAlignment(.center)
                        }
                        HStack(spacing: 16) {
                            Button("Restore") { Task { await app.pro.restore() } }
                            Link("Terms", destination: Legal.terms)
                            Link("Privacy", destination: Legal.privacy)
                        }
                        .font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                    }
                    .padding(.horizontal, Mono.gutter)
                    .padding(.top, 12)
                    .animation(Motion.glide, value: plan)
                }
                .background(Mono.bg)
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Not now") { dismiss() } } }
        }
        .onChange(of: app.pro.isPro) { _, isPro in if isPro { dismiss() } }
        .presentationDragIndicator(.visible)
    }

    private func planRow(_ p: Plan, title: String, price: String, note: String) -> some View {
        let on = plan == p
        return Button { withAnimation(Motion.snap) { plan = p }; Haptics.tap() } label: {
            HStack(spacing: 14) {
                Icon(on ? "radio-button-fill" : "circle", size: 22)
                    .contentTransition(.opacity)
                    .foregroundStyle(on ? Mono.ink : Mono.faint)
                    .pop(on: on, scale: on ? 1.3 : 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Mono.sans(.body, weight: .semibold))
                    Text(note).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
                }
                Spacer()
                Text(price).font(Mono.data(.subheadline, weight: .medium))
            }
            .padding(16)
            .background(on ? Mono.tile : .clear, in: RoundedRectangle(cornerRadius: Mono.radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Mono.radius, style: .continuous)
                .strokeBorder(on ? Mono.ink : Mono.line, lineWidth: on ? 1.5 : 1))
            .scaleEffect(on ? 1 : 0.98)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.97))
    }
}

// MARK: - Import status + toast

/// Progress of the running import, or the summary of the one that just finished.
/// Lives in the library's bottom bar (and floats on Home only if a paused run outlives the session).
struct ImportStatusBar: View {
    @Environment(AppState.self) private var app

    var body: some View {
        let imp = app.importer
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Mono.sans(.subheadline, weight: .semibold)).monospacedDigit()
                        .contentTransition(.numericText())
                        .animation(Motion.snap, value: title)
                    Text(subtitle).font(Mono.data(.caption)).foregroundStyle(Mono.dim).lineLimit(1)
                        .rollingText(subtitle)
                }
                Spacer()
                if imp.isRunning {
                    Button("Cancel") { imp.cancelAll() }.buttonStyle(OutlineChipStyle())
                        .transition(.popIn)
                } else {
                    Button("Open Photos") {
                        if let u = URL(string: "photos-redirect://") { UIApplication.shared.open(u) }
                    }
                    .buttonStyle(OutlineChipStyle(filled: true))
                    .transition(.popIn)
                }
            }
            if imp.isRunning {
                ZStack(alignment: .leading) {
                    Capsule().fill(Mono.tile)
                    Capsule().fill(imp.isPaused ? Mono.dim : Mono.accent)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .scaleEffect(x: max(0.02, imp.progress), anchor: .leading)
                        .animation(Motion.progress, value: imp.progress)
                        .sheen(!imp.isPaused, opacity: 0.55)
                }
                .frame(height: 4)
                .clipShape(Capsule())
                .transition(.opacity.combined(with: .scale(scale: 0.6, anchor: .leading)))
            }
        }
        .animation(Motion.glide, value: imp.isRunning)
        .frame(minHeight: 50)
        .onChange(of: imp.isRunning) { was, now in if was && !now { Haptics.success() } }
    }

    private var title: String {
        let imp = app.importer
        if imp.isRunning {
            if imp.isPaused { return "Paused · \(imp.completedInRun) of \(imp.totalInRun)" }
            return "Importing \(min(imp.completedInRun + 1, imp.totalInRun)) of \(imp.totalInRun)"
        }
        guard let run = imp.lastFinishedRun else { return "" }
        return "\(run.count) saved to Photos"
    }

    private var subtitle: String {
        let imp = app.importer
        if imp.isRunning {
            switch imp.pauseReason {
            case .disconnected: return "Camera disconnected. Plug it back in to continue."
            case .background: return "Continues when Unroll is open."
            case nil: return imp.throughputMBps.map { String(format: "%.0f MB/s", $0) } ?? (imp.current?.primary.name ?? "Starting…")
            }
        }
        guard let run = imp.lastFinishedRun else { return "" }
        var parts = [String(format: "%.1f s", run.seconds)]
        if run.failed > 0 { parts.append("\(run.failed) failed") }
        if run.geotagged > 0 { parts.append("\(run.geotagged) with location") }
        return parts.joined(separator: " · ")
    }
}

/// Floating import status, shown only when no session screen is there to host it.
struct ImportHUD: View {
    @Environment(AppState.self) private var app
    var body: some View {
        let show = app.session == nil && (app.importer.isRunning || app.importer.showingSummary)
        Group {
            if show {
                ImportStatusBar()
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Mono.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Mono.line, lineWidth: 0.5))
                    .padding(.horizontal, 12)
                    .transition(.riseFromBottom)
            }
        }
        .animation(Motion.glide, value: show)
    }
}

struct ToastView: View {
    @Environment(AppState.self) private var app
    var body: some View {
        Group {
            if let t = app.toast ?? app.importer.lastError {
                Text(t)
                    .id(t)
                    .font(Mono.sans(.subheadline, weight: .medium))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Mono.ink)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(Mono.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Mono.line, lineWidth: 0.5))
                    .padding(.horizontal, 20)
                    .transition(.asymmetric(
                        insertion: .move(edge: .bottom).combined(with: .scale(scale: 0.85, anchor: .bottom)).combined(with: .opacity),
                        removal: .scale(scale: 0.92).combined(with: .opacity)))
                    .task(id: t) {
                        try? await Task.sleep(for: .seconds(3.5))
                        app.toast = nil
                        app.importer.lastError = nil
                    }
            }
        }
        .animation(Motion.pop, value: app.toast)
        .animation(Motion.pop, value: app.importer.lastError)
    }
}
