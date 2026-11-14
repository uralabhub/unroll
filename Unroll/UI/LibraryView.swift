import SwiftUI

enum ShotFilter: String, CaseIterable, Identifiable {
    case all, keepers, rated, locked, new, raw, today
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "All"
        case .keepers: return "Picks"
        case .rated: return "Rated"
        case .locked: return "Protected"
        case .new: return "New"
        case .raw: return "RAW"
        case .today: return "Today"
        }
    }
    func matches(_ s: Shot) -> Bool {
        switch self {
        case .all: return true
        case .keepers: return s.isKeeper
        case .rated: return s.isRated
        case .locked: return s.isLocked
        case .new: return !s.wasImported
        case .raw: return s.raw != nil || s.primary.format.isRAW
        case .today: return Calendar.current.isDateInToday(s.captureDate)
        }
    }
}

struct LibraryView: View {
    @Environment(AppState.self) private var app
    let session: CameraSession

    @State private var filter: ShotFilter = .all
    @State private var minStars = 1
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var detail: ShotRoute?
    @State private var recapShots: RecapRoute?
    @State private var columns = 3
    @Namespace private var zoom

    private var visible: [Shot] {
        session.shots.filter { s in
            guard filter.matches(s) else { return false }
            if filter == .rated { return s.rating >= minStars }
            return true
        }
    }

    private var days: [(Date, [Shot])] {
        let groups = Dictionary(grouping: visible) { Calendar.current.startOfDay(for: $0.fileDate) }
        return groups.keys.sorted(by: >).map { ($0, groups[$0]!) }
    }

    private var keepersNotImported: [Shot] { session.shots.filter { $0.isKeeper && !$0.wasImported && !$0.isVideo } }
    private var newShots: [Shot] { session.shots.filter { !$0.wasImported && !$0.isVideo } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SessionHeader(session: session)
                filterBar
                if case .failed(let msg) = session.status {
                    QuietMessage(title: "Connection lost", message: msg)
                } else if session.shots.isEmpty {
                    loadingState
                } else if visible.isEmpty {
                    emptyFilter
                } else {
                    grid
                }
            }
            .animation(Motion.glide, value: filter)
            .animation(Motion.snap, value: minStars)
            .padding(.bottom, 160)
        }
        .scrollIndicators(.hidden)
        .background(Mono.bg)
        .navigationTitle(session.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom) { actionBar }
        .navigationDestination(item: $detail) { r in
            ShotPagerView(shots: r.shots, startID: r.startID, session: session)
                .zoomDestination(id: r.startID, in: zoom)
        }
        .sheet(item: $recapShots) { r in RecapBuilderView(shots: r.shots) }
    }

    // MARK: Pieces

    private var filterBar: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabStrip(items: ShotFilter.allCases, selection: $filter, label: \.label,
                     count: { f in f == .all ? session.shots.count : session.shots.filter(f.matches).count })
            Hairline()
            if filter == .rated {
                HStack(spacing: 14) {
                    CapsLabel("At least")
                    HStack(spacing: 6) {
                        ForEach(1...5, id: \.self) { n in
                            Icon(n <= minStars ? "star-fill" : "star", size: 18)
                                .foregroundStyle(n <= minStars ? Mono.ink : Mono.faint)
                                .frame(width: 28, height: 28)
                                .contentShape(Rectangle())
                                .onTapGesture { minStars = n; Haptics.tap() }
                        }
                    }
                    Spacer()
                    if !session.scanComplete {
                        Text("Reading \(session.scanned)/\(session.shots.count)").font(Mono.data(.caption2)).foregroundStyle(Mono.dim)
                    }
                }
                .padding(.horizontal, Mono.gutter)
                .padding(.vertical, 6)
                Hairline()
            }
        }
    }

    private var grid: some View {
        let cols = Array(repeating: GridItem(.flexible(), spacing: 1), count: columns)
        return LazyVStack(alignment: .leading, spacing: 22, pinnedViews: []) {
            Color.clear.frame(height: 0)
            ForEach(days, id: \.0) { day, shots in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(dayTitle(day)).font(Mono.sans(.title3, weight: .bold))
                        Text("\(shots.count)").font(Mono.data(.footnote)).foregroundStyle(Mono.dim)
                            .rollingNumber(shots.count)
                        Spacer()
                        if selecting {
                            let allIn = shots.allSatisfy { selection.contains($0.id) }
                            Button(allIn ? "Deselect day" : "Select day") {
                                if allIn { shots.forEach { selection.remove($0.id) } } else { shots.forEach { selection.insert($0.id) } }
                                Haptics.tap()
                            }
                            .font(Mono.sans(.footnote, weight: .medium))
                            .foregroundStyle(Mono.ink)
                        }
                    }
                    .padding(.horizontal, Mono.gutter)
                    LazyVGrid(columns: cols, spacing: 2) {
                        ForEach(Array(shots.enumerated()), id: \.element.id) { i, shot in
                            ShotCell(shot: shot, selecting: selecting, selected: selection.contains(shot.id))
                                .zoomSource(id: shot.id, in: zoom)
                                .onTapGesture { tap(shot) }
                                .onLongPressGesture { startSelecting(with: shot) }
                                .onAppear { session.prioritize(shot) }
                                .rise(i % 24, step: 0.025, distance: 12)
                                .transition(.scale(scale: 0.85).combined(with: .opacity))
                        }
                    }
                    .animation(Motion.glide, value: columns)
                }
            }
        }
    }

    private var loadingState: some View {
        VStack(spacing: 14) {
            ProgressView()
            switch session.status {
            case .connecting: Text("Opening session…")
            case .cataloging(let p): Text(p > 0 ? "Reading card  \(p)%" : "Reading card…")
            default: Text("No photos on this card")
            }
        }
        .font(Mono.data(.footnote))
        .foregroundStyle(Mono.dim)
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private var emptyFilter: some View {
        QuietMessage(
            title: filter == .new ? "All caught up" : "Nothing here",
            message: [.keepers, .rated, .locked].contains(filter)
                ? "Rate or protect shots on the camera body and they show up here. Picks are shots that are rated or protected."
                : (filter == .new ? "Everything on this card is already in Photos." : "No shots match this filter.")
        )
        .padding(.top, 12)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { app.disconnect() } label: { Icon("caret-left", size: 20) }
                .accessibilityLabel("Disconnect")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button(selecting ? "Done" : "Select") {
                withAnimation(Motion.glide) { selecting.toggle(); if !selecting { selection.removeAll() } }
            }
            Menu {
                Picker("Grid", selection: $columns) {
                    Label { Text("Large") } icon: { Image("ph.squares-four") }.tag(2)
                    Label { Text("Medium") } icon: { Image("ph.grid-nine") }.tag(3)
                    Label { Text("Small") } icon: { Image("ph.dots-nine") }.tag(5)
                }
                Button { recapShots = RecapRoute(shots: recapCandidates) } label: { Label { Text("Make a recap card") } icon: { Image("ph.cards") } }
                Divider()
                Button(role: .destructive) { app.disconnect() } label: { Label { Text("Disconnect") } icon: { Image("ph.eject") } }
            } label: { Icon("dots-three", size: 22) }
        }
    }

    private var recapCandidates: [Shot] {
        let picked = session.shots.filter { selection.contains($0.id) }
        if !picked.isEmpty { return picked }
        let keepers = session.shots.filter(\.isKeeper)
        return keepers.isEmpty ? Array(session.shots.prefix(12)) : keepers
    }

    @ViewBuilder
    private var actionBar: some View {
        let selected = session.shots.filter { selection.contains($0.id) }
        VStack(spacing: 0) {
            Group {
                if selecting {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(selected.isEmpty ? "Tap to select" : "\(selected.count) selected").font(Mono.sans(.subheadline, weight: .semibold))
                                .rollingNumber(selected.count)
                            if !selected.isEmpty {
                                Text(ByteCountFormatter.string(fromByteCount: selected.reduce(0) { $0 + $1.totalBytes }, countStyle: .file))
                                    .font(Mono.data(.caption2)).foregroundStyle(Mono.dim)
                            }
                        }
                        Spacer()
                        Button { recapShots = RecapRoute(shots: selected) } label: { Text("Recap") }
                            .buttonStyle(SecondaryButtonStyle()).frame(width: 92)
                            .disabled(selected.isEmpty)
                        Button {
                            app.importShots(selected)
                            withAnimation { selecting = false; selection.removeAll() }
                        } label: { Text("Import") }
                        .buttonStyle(PrimaryButtonStyle()).frame(width: 110)
                        .disabled(selected.isEmpty)
                    }
                } else if !app.importer.isRunning {
                    HStack(spacing: 10) {
                        Button { app.importShots(keepersNotImported) } label: {
                            Text("Import picks  \(keepersNotImported.count)\(session.scanComplete ? "" : "+")")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(keepersNotImported.isEmpty)
                        Button { app.importShots(newShots) } label: { Text("All new  \(newShots.count)") }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(newShots.isEmpty)
                    }
                }
            }
            .padding(.horizontal, Mono.gutter)
            .padding(.top, 12)
            .padding(.bottom, 6)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .animation(Motion.glide, value: selecting)
            .animation(Motion.glide, value: app.importer.isRunning)
        }
        .background(.regularMaterial)
    }

    // MARK: Actions

    private func tap(_ shot: Shot) {
        if selecting {
            if selection.contains(shot.id) { selection.remove(shot.id) } else { selection.insert(shot.id) }
            Haptics.tap()
        } else {
            detail = ShotRoute(shots: visible, startID: shot.id)
        }
    }

    private func startSelecting(with shot: Shot) {
        withAnimation(Motion.glide) { selecting = true }
        selection.insert(shot.id)
        Haptics.tap()
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

struct SessionHeader: View {
    let session: CameraSession

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                stat(session.shots.count, "shots")
                stat(session.shots.filter(\.isKeeper).count, "picks")
                stat(session.shots.filter(\.wasImported).count, "imported")
                stat(session.kind.label, "source")
            }
            .padding(.horizontal, Mono.gutter)
            .padding(.vertical, 12)
            if let progress {
                GeometryReader { g in
                    Capsule().fill(Mono.ink).frame(width: g.size.width * progress)
                }
                .frame(height: 2)
                .animation(Motion.snap, value: progress)
                .transition(.opacity)
            }
            if let line = statusLine {
                Text(line).font(Mono.data(.caption2)).foregroundStyle(Mono.dim)
                    .padding(.horizontal, Mono.gutter).padding(.bottom, 10).padding(.top, progress == nil ? 0 : 8)
            }
        }
    }

    private func stat(_ value: Int, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(Mono.data(.title3, weight: .medium)).lineLimit(1).minimumScaleFactor(0.7)
                .rollingNumber(value)
            Text(label.uppercased()).font(Mono.caps(10)).tracking(1).foregroundStyle(Mono.dim)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(Mono.data(.title3, weight: .medium)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label.uppercased()).font(Mono.caps(10)).tracking(1).foregroundStyle(Mono.dim)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var progress: Double? {
        switch session.status {
        case .cataloging(let p): return Double(p) / 100
        case .ready where !session.scanComplete && !session.shots.isEmpty:
            return Double(session.scanned) / Double(session.shots.count)
        default: return nil
        }
    }

    private var statusLine: String? {
        switch session.status {
        case .connecting: return "Connecting over \(session.kind.label)…"
        case .cataloging(let p): return "Cataloging card  \(p)%"
        case .failed: return nil
        case .ready:
            return session.scanComplete ? nil : "Reading ratings  \(session.scanned)/\(session.shots.count)"
        }
    }
}

struct ShotCell: View {
    let shot: Shot
    let selecting: Bool
    let selected: Bool

    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Mono.raised
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let t = shot.thumbnail {
                    Image(uiImage: t).resizable().scaledToFill()
                        .transition(reduce ? .opacity : .modifier(active: Developing(done: false), identity: Developing(done: true)))
                } else if shot.isVideo {
                    Icon("video-camera", size: 22).foregroundStyle(Mono.dim)
                }
            }
            .clipped()
            .overlay(alignment: .topLeading) {
                if shot.isKeeper {
                    HStack(spacing: 3) {
                        if shot.rating > 0 {
                            Icon("star-fill", size: 9)
                            Text("\(shot.rating)")
                        }
                        if shot.isLocked { Icon("lock-simple-fill", size: 9) }
                    }
                    .font(Mono.sans(size: 10, weight: .heavy).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 4).padding(.vertical, 3)
                    .background(.black.opacity(0.7))
                }
            }
            .overlay(alignment: .bottomLeading) {
                if shot.raw != nil || shot.primary.format.isRAW || shot.isVideo {
                    Text(shot.isVideo ? "VID" : (shot.raw != nil ? "J+R" : "RAW"))
                        .font(Mono.sans(size: 10, weight: .heavy).monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 4).padding(.vertical, 3)
                        .background(.black.opacity(0.7))
                }
            }
            .overlay(alignment: .bottomTrailing) { stateBadge }
            .overlay {
                if selecting {
                    ZStack(alignment: .topTrailing) {
                        if selected { Rectangle().strokeBorder(Mono.ink, lineWidth: 3) }
                        Icon(selected ? "check-circle-fill" : "circle", size: 22)
                            .foregroundStyle(.white)
                            .background(Circle().fill(selected ? Mono.ink : .clear).padding(2))
                            .shadow(color: .black.opacity(0.3), radius: 1)
                            .scaleEffect(selected ? 1 : 0.85)
                            .padding(6)
                    }
                    .transition(.opacity)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: selected ? 10 : 2, style: .continuous))
            .scaleEffect(selected && !reduce ? 0.9 : 1)
            .animation(Motion.press, value: selected)
            .animation(Motion.snap, value: selecting)
            .contentShape(Rectangle())
            .task(id: shot.id) { await Previews.thumbnail(shot) }
            .animation(.easeOut(duration: 0.55), value: shot.thumbnail != nil)
            .sensoryFeedback(.selection, trigger: selected)
    }

    @ViewBuilder private var stateBadge: some View {
        switch shot.importState {
        case .queued: badge(Icon("clock", size: 10))
        case .importing(let p): badge(Text("\(Int(p * 100))%"))
        case .failed: badge(Icon("warning", size: 10))
        case .done, .none:
            if shot.wasImported { badge(Icon("check", size: 10)) }
        }
    }

    private func badge(_ v: some View) -> some View {
        v.font(Mono.sans(size: 10, weight: .heavy).monospacedDigit())
        .foregroundStyle(.white)
        .padding(.horizontal, 4).padding(.vertical, 3)
        .background(.black.opacity(0.7))
    }
}
