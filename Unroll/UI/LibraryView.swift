import SwiftUI

/// The three filters that cover almost every visit. Everything else lives in `FilterOptions`.
enum ShotFilter: String, CaseIterable, Identifiable {
    case all, new, picks
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "All"
        case .new: return "Not imported"
        case .picks: return "Picks"
        }
    }
    func matches(_ s: Shot) -> Bool {
        switch self {
        case .all: return true
        case .new: return !s.wasImported
        case .picks: return s.isKeeper
        }
    }
}

/// Secondary filters, in the filter menu.
struct FilterOptions: Equatable {
    var minStars = 0
    var protectedOnly = false
    var rawOnly = false
    var todayOnly = false
    var isActive: Bool { minStars > 0 || protectedOnly || rawOnly || todayOnly }
    func matches(_ s: Shot) -> Bool {
        if minStars > 0 && s.rating < minStars { return false }
        if protectedOnly && !s.isLocked { return false }
        if rawOnly && !(s.raw != nil || s.primary.format.isRAW) { return false }
        if todayOnly && !Calendar.current.isDateInToday(s.captureDate) { return false }
        return true
    }
}

/// Everything the library screen shows, computed once per catalog revision / filter change,
/// outside the view body, so header reads and import ticks don't re-render the whole grid.
struct LibrarySnapshot: Equatable {
    struct Day: Identifiable, Equatable {
        let id: Date
        let shots: [Shot]
    }
    var days: [Day] = []
    var visible: [Shot] = []
    var counts: [ShotFilter: Int] = [:]
    var picksToImport: [Shot] = []
    var newToImport: [Shot] = []
    var total = 0

    init() {}

    @MainActor
    init(_ session: CameraSession, filter: ShotFilter, options: FilterOptions) {
        let all = session.shots
        total = all.count
        var nNew = 0, nPicks = 0
        var picks: [Shot] = [], fresh: [Shot] = [], vis: [Shot] = []
        for s in all {
            let isNew = !s.wasImported
            let isPick = s.isKeeper
            if isNew { nNew += 1 }
            if isPick { nPicks += 1 }
            if isNew && !s.isVideo { fresh.append(s); if isPick { picks.append(s) } }
            if filter.matches(s) && options.matches(s) { vis.append(s) }
        }
        counts = [.all: all.count, .new: nNew, .picks: nPicks]
        picksToImport = picks
        newToImport = fresh
        visible = vis
        let cal = Calendar.current
        var order: [Date] = []
        var groups: [Date: [Shot]] = [:]
        for s in vis {   // already newest-first
            let d = cal.startOfDay(for: s.fileDate)
            if groups[d] == nil { order.append(d) }
            groups[d, default: []].append(s)
        }
        days = order.map { Day(id: $0, shots: groups[$0]!) }
    }
}

struct LibraryView: View {
    @Environment(AppState.self) private var app
    let session: CameraSession

    @State private var filter: ShotFilter = .all
    @State private var options = FilterOptions()
    @State private var snapshot = LibrarySnapshot()
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var detail: ShotRoute?
    @State private var recapShots: RecapRoute?
    @State private var tool: ToolRoute?
    @State private var columns = 4
    @Namespace private var zoom

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section {
                    content
                } header: {
                    filterBar
                }
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(Mono.bg)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden()
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            LibraryBottomBar(session: session, snapshot: snapshot, selecting: $selecting, selection: $selection,
                             recap: { recapShots = RecapRoute(shots: $0) })
        }
        .navigationDestination(item: $detail) { r in
            ShotPagerView(shots: r.shots, startID: r.startID, session: session)
                .zoomDestination(id: r.startID, in: zoom)
        }
        .navigationDestination(item: $tool) { $0.destination }
        .sheet(item: $recapShots) { r in RecapBuilderView(shots: r.shots) }
        .onAppear(perform: rebuild)
        .onChange(of: session.revision) { rebuild() }
        .onChange(of: filter) { withAnimation(Motion.snap) { rebuild() } }
        .onChange(of: options) { withAnimation(Motion.snap) { rebuild() } }
    }

    private func rebuild() {
        let s = LibrarySnapshot(session, filter: filter, options: options)
        if s != snapshot { snapshot = s }
    }

    // MARK: Pieces

    @ViewBuilder private var content: some View {
        if case .failed(let msg) = session.status {
            QuietMessage(title: "Connection lost", message: msg)
        } else if session.shots.isEmpty {
            LoadingState(session: session)
        } else if snapshot.visible.isEmpty {
            emptyFilter
        } else {
            grid
        }
    }

    private var filterBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                TabStrip(items: ShotFilter.allCases, selection: $filter, label: \.label,
                         count: { snapshot.counts[$0] }, scrolls: true)
                filterMenu.padding(.trailing, Mono.gutter - 4)
            }
            .padding(.vertical, 8)
            ScanProgressLine(session: session)
        }
        .background(.bar)
    }

    private var filterMenu: some View {
        Menu {
            Picker("Stars", selection: $options.minStars) {
                Text("Any rating").tag(0)
                ForEach(1...5, id: \.self) { n in Text(String(repeating: "★", count: n) + (n < 5 ? " or more" : "")).tag(n) }
            }
            Toggle("Protected only", isOn: $options.protectedOnly)
            Toggle("RAW only", isOn: $options.rawOnly)
            Toggle("Shot today", isOn: $options.todayOnly)
            if options.isActive {
                Divider()
                Button("Clear filters") { options = FilterOptions() }
            }
        } label: {
            Image(systemName: options.isActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle").font(.system(size: 20))
                .foregroundStyle(options.isActive ? Mono.accent : Mono.dim)
                .frame(width: 40, height: 32)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("More filters")
    }

    private var grid: some View {
        let cols = Array(repeating: GridItem(.flexible(), spacing: 2), count: columns)
        return ForEach(snapshot.days) { day in
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(dayTitle(day.id)).font(Mono.sans(.subheadline, weight: .semibold))
                    Text("\(day.shots.count)").font(Mono.data(.footnote)).foregroundStyle(Mono.dim)
                    Spacer()
                    if selecting {
                        let allIn = day.shots.allSatisfy { selection.contains($0.id) }
                        Button(allIn ? "Deselect" : "Select all") {
                            if allIn { day.shots.forEach { selection.remove($0.id) } } else { day.shots.forEach { selection.insert($0.id) } }
                            Haptics.select()
                        }
                        .font(Mono.sans(.footnote, weight: .medium))
                    }
                }
                .padding(.horizontal, Mono.gutter)
                .padding(.top, 18).padding(.bottom, 8)
                LazyVGrid(columns: cols, spacing: 2) {
                    ForEach(day.shots) { shot in
                        ShotCell(shot: shot, selecting: selecting, selected: selection.contains(shot.id))
                            .zoomSource(id: shot.id, in: zoom)
                            .onTapGesture { tap(shot) }
                            .contextMenu { cellMenu(shot) }
                            .onAppear { session.prioritize(shot) }
                    }
                }
            }
        }
    }

    @ViewBuilder private func cellMenu(_ shot: Shot) -> some View {
        Button { detail = ShotRoute(shots: snapshot.visible, startID: shot.id) } label: { Label("Open", systemImage: "arrow.up.left.and.arrow.down.right") }
        if !shot.wasImported && !shot.isVideo || shot.isVideo && !shot.wasImported {
            Button { app.importShots([shot]) } label: { Label("Import", systemImage: "square.and.arrow.down") }
        }
        Button {
            withAnimation(Motion.snap) { selecting = true }
            selection.insert(shot.id)
        } label: { Label("Select", systemImage: "checkmark.circle") }
    }

    private var emptyFilter: some View {
        let message: String = {
            if filter == .new && !options.isActive { return "Everything on this card is already in Photos." }
            if filter == .picks && !options.isActive { return "Rate or protect shots on the camera and they show up here." }
            return "No shots match these filters."
        }()
        return QuietMessage(title: filter == .new && !options.isActive ? "All caught up" : "Nothing here", message: message)
            .padding(.top, 12)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(spacing: 1) {
                Text(session.name).font(Mono.sans(.headline)).lineLimit(1)
                SessionSubtitle(session: session, snapshot: snapshot)
            }
        }
        ToolbarItem(placement: .topBarLeading) {
            Menu {
                Section(session.kind.label) {
                    Button(role: .destructive) { app.disconnect() } label: { Label("Disconnect", systemImage: "eject") }
                }
            } label: {
                Icon(session.kind.symbol, size: 22).foregroundStyle(Mono.ink)
            }
            .accessibilityLabel("Connection")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button(selecting ? "Done" : "Select") {
                withAnimation(Motion.snap) { selecting.toggle(); if !selecting { selection.removeAll() } }
            }
            .font(Mono.sans(.body, weight: selecting ? .semibold : .regular))
            Menu {
                Picker("Grid size", selection: $columns) {
                    Text("Large tiles").tag(3)
                    Text("Medium tiles").tag(4)
                    Text("Small tiles").tag(6)
                }
                Button { recapShots = RecapRoute(shots: recapCandidates) } label: { Label("Make a recap card", systemImage: "rectangle.portrait.on.rectangle.portrait") }
                Section("Tools") {
                    Button { tool = .walks } label: { Label("Photo walks", systemImage: "map") }
                    Button { tool = .recipes } label: { Label("Recipes", systemImage: "camera.filters") }
                    Button { tool = .clockSync } label: { Label("Clock sync", systemImage: "clock") }
                    Button { tool = .settings } label: { Label("Settings", systemImage: "gearshape") }
                }
                Section {
                    Button(role: .destructive) { app.disconnect() } label: { Label("Disconnect", systemImage: "eject") }
                }
            } label: { Image(systemName: "ellipsis.circle").font(.system(size: 19)) }
            .accessibilityLabel("More")
        }
    }

    private var recapCandidates: [Shot] {
        let picked = snapshot.visible.filter { selection.contains($0.id) }
        if !picked.isEmpty { return picked }
        let keepers = session.shots.filter(\.isKeeper)
        return keepers.isEmpty ? Array(session.shots.prefix(12)) : keepers
    }

    // MARK: Actions

    private func tap(_ shot: Shot) {
        if selecting {
            if selection.contains(shot.id) { selection.remove(shot.id) } else { selection.insert(shot.id) }
            Haptics.select()
        } else {
            detail = ShotRoute(shots: snapshot.visible, startID: shot.id)
        }
    }

    private func dayTitle(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return "Today" }
        if Calendar.current.isDateInYesterday(d) { return "Yesterday" }
        return d.formatted(.dateTime.weekday(.wide).day().month(.abbreviated))
    }
}

struct ShotRoute: Hashable {
    let shots: [Shot]
    let startID: String
}

struct RecapRoute: Identifiable {
    let id = UUID()
    let shots: [Shot]
}

// MARK: - Status pieces (each observes only what it shows)

/// "342 photos · 128 not imported", or what the connection is doing.
private struct SessionSubtitle: View {
    let session: CameraSession
    let snapshot: LibrarySnapshot
    var body: some View {
        Text(text).font(Mono.data(.caption)).foregroundStyle(Mono.dim).lineLimit(1)
            .contentTransition(.numericText())
            .animation(Motion.snap, value: text)
    }
    private var text: String {
        switch session.status {
        case .connecting: return "Connecting…"
        case .cataloging(let p): return p > 0 ? "Reading card · \(p)%" : "Reading card…"
        case .failed: return "Disconnected"
        case .ready:
            let new = snapshot.counts[.new] ?? 0
            let base = "\(snapshot.total) photos" + (new > 0 && new < snapshot.total ? " · \(new) not imported" : "")
            return base
        }
    }
}

/// A 2 pt line under the filters while the card is cataloged or ratings are being read.
private struct ScanProgressLine: View {
    let session: CameraSession
    var body: some View {
        let p = progress
        ZStack(alignment: .leading) {
            Rectangle().fill(Mono.line.opacity(p == nil ? 0 : 0.5))
            if let p {
                Rectangle().fill(Mono.accent).frame(maxWidth: .infinity, alignment: .leading)
                    .scaleEffect(x: max(0.02, p), anchor: .leading)
                    .animation(Motion.progress, value: p)
            }
        }
        .frame(height: p == nil ? 0.5 : 2)
        .animation(Motion.glide, value: p == nil)
        .accessibilityHidden(p == nil)
        .accessibilityLabel("Reading ratings")
    }
    private var progress: Double? {
        switch session.status {
        case .cataloging(let p): return Double(p) / 100
        case .ready where !session.scanComplete && session.scannable > 0:
            return Double(session.scanned) / Double(session.scannable)
        default: return nil
        }
    }
}

private struct LoadingState: View {
    let session: CameraSession
    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text(text).font(Mono.sans(.footnote)).foregroundStyle(Mono.dim)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
    }
    private var text: String {
        switch session.status {
        case .connecting: return "Opening the camera…"
        case .cataloging(let p): return p > 0 ? "Reading card · \(p)%" : "Reading card…"
        default: return "No photos on this card"
        }
    }
}

// MARK: - Bottom bar: one smart import button, the selection, or the running import

private struct LibraryBottomBar: View {
    @Environment(AppState.self) private var app
    let session: CameraSession
    let snapshot: LibrarySnapshot
    @Binding var selecting: Bool
    @Binding var selection: Set<String>
    let recap: ([Shot]) -> Void

    var body: some View {
        let imp = app.importer
        VStack(spacing: 0) {
            Group {
                if selecting {
                    selectionBar
                } else if imp.isRunning || imp.showingSummary {
                    ImportStatusBar()
                } else {
                    importBar
                }
            }
            .padding(.horizontal, Mono.gutter)
            .padding(.top, 10)
            .padding(.bottom, 8)
            .transition(.opacity)
        }
        .animation(Motion.glide, value: selecting)
        .animation(Motion.glide, value: imp.isRunning)
        .background(.bar)
        .overlay(alignment: .top) { Hairline() }
    }

    @ViewBuilder private var importBar: some View {
        let picks = snapshot.picksToImport, fresh = snapshot.newToImport
        if !picks.isEmpty {
            HStack(spacing: 10) {
                Button { app.importShots(picks) } label: { Text("Import \(picks.count) \(picks.count == 1 ? "pick" : "picks")") }
                    .buttonStyle(PrimaryButtonStyle())
                if fresh.count > picks.count {
                    Button { app.importShots(fresh) } label: { Text("All \(fresh.count)") }
                        .buttonStyle(SecondaryButtonStyle())
                        .frame(width: 112)
                }
            }
        } else if !fresh.isEmpty {
            Button { app.importShots(fresh) } label: { Text("Import \(fresh.count) new \(fresh.count == 1 ? "photo" : "photos")") }
                .buttonStyle(PrimaryButtonStyle())
        } else if !session.shots.isEmpty {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle")
                Text("Everything here is in Photos")
            }
            .font(Mono.sans(.subheadline)).foregroundStyle(Mono.dim)
            .frame(maxWidth: .infinity, minHeight: 50)
        }
    }

    private var selectionBar: some View {
        let selected = snapshot.visible.filter { selection.contains($0.id) }
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(selected.isEmpty ? "Select photos" : "\(selected.count) selected")
                    .font(Mono.sans(.subheadline, weight: .semibold)).monospacedDigit()
                if !selected.isEmpty {
                    Text(ByteCountFormatter.string(fromByteCount: selected.reduce(0) { $0 + $1.totalBytes }, countStyle: .file))
                        .font(Mono.data(.caption)).foregroundStyle(Mono.dim)
                }
            }
            Spacer()
            Button { recap(selected) } label: { Text("Recap") }
                .buttonStyle(SecondaryButtonStyle()).frame(width: 90)
                .disabled(selected.isEmpty)
            Button {
                app.importShots(selected)
                withAnimation(Motion.snap) { selecting = false; selection.removeAll() }
            } label: { Text("Import") }
                .buttonStyle(PrimaryButtonStyle()).frame(width: 110)
                .disabled(selected.isEmpty)
        }
    }
}

// MARK: - Cell

struct ShotCell: View {
    let shot: Shot
    let selecting: Bool
    let selected: Bool
    @State private var image: UIImage?

    var body: some View {
        Mono.tile
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .transition(.opacity)
                } else if shot.isVideo {
                    Icon("video-camera", size: 20).foregroundStyle(Mono.dim)
                }
            }
            .clipped()
            .overlay(alignment: .topLeading) { keeperBadge }
            .overlay(alignment: .bottomLeading) {
                if shot.isVideo, image != nil {
                    Image(systemName: "video.fill").font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.white).shadow(color: .black.opacity(0.6), radius: 2)
                        .padding(5)
                }
            }
            .overlay { importOverlay }
            .overlay(alignment: .topTrailing) { selectionMark }
            .contentShape(Rectangle())
            .task(id: shot.id) {
                if let hit = shot.thumbnail { image = hit; return }
                if let img = await Previews.thumbnail(shot) {
                    withAnimation(.easeOut(duration: 0.2)) { image = img }
                }
            }
            .accessibilityElement()
            .accessibilityLabel(accessibilityText)
            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder private var keeperBadge: some View {
        if shot.isKeeper {
            HStack(spacing: 2) {
                if shot.rating > 0 {
                    Image(systemName: "star.fill")
                    Text("\(shot.rating)")
                }
                if shot.isLocked { Image(systemName: "lock.fill") }
            }
            .font(.system(size: 10, weight: .bold).monospacedDigit())
            .foregroundStyle(.white)
            .padding(.horizontal, 5).padding(.vertical, 3)
            .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .padding(4)
        }
    }

    /// Queued: dimmed. Importing: a thin fill along the bottom. Done: a small check.
    @ViewBuilder private var importOverlay: some View {
        switch shot.importState {
        case .queued:
            Color.black.opacity(0.35)
        case .importing(let f):
            ZStack(alignment: .bottomLeading) {
                Color.black.opacity(0.25)
                Rectangle().fill(Mono.accent).frame(height: 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .scaleEffect(x: max(0.03, f), anchor: .leading)
                    .animation(Motion.progress, value: f)
            }
        case .failed:
            ZStack {
                Color.black.opacity(0.45)
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 16)).foregroundStyle(.white)
            }
        case .done, .none:
            if shot.wasImported && !selecting {
                ZStack(alignment: .bottomTrailing) {
                    Color.clear
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.black, .white)
                        .padding(4)
                        .transition(.opacity.combined(with: .scale(scale: 0.6)))
                }
            }
        }
    }

    @ViewBuilder private var selectionMark: some View {
        if selecting {
            ZStack(alignment: .topTrailing) {
                if selected { Color.black.opacity(0.25) }
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20, weight: .regular))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(selected ? Color.black : .white, .white)
                    .shadow(color: .black.opacity(0.35), radius: 1.5)
                    .padding(5)
            }
            .transition(.opacity)
            .animation(Motion.snap, value: selected)
            .sensoryFeedback(.selection, trigger: selected)
        }
    }

    private var accessibilityText: String {
        var parts = [shot.primary.name]
        if shot.rating > 0 { parts.append("\(shot.rating) stars") }
        if shot.isLocked { parts.append("protected") }
        if shot.wasImported { parts.append("in Photos") }
        if selected { parts.append("selected") }
        return parts.joined(separator: ", ")
    }
}
