import SwiftUI

enum ShotFilter: String, CaseIterable, Identifiable {
    case all, keepers, rated, locked, new, raw, today
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "All"
        case .keepers: return "★ + 🔒"
        case .rated: return "★ Rated"
        case .locked: return "🔒 Protected"
        case .new: return "Not imported"
        case .raw: return "Has RAW"
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
            VStack(alignment: .leading, spacing: 12) {
                SessionHeader(session: session)
                    .padding(.horizontal, 16)
                filterBar
                if case .failed(let msg) = session.status {
                    ContentUnavailableView("Connection lost", systemImage: "cable.connector.slash", description: Text(msg))
                } else if session.shots.isEmpty {
                    loadingState
                } else if visible.isEmpty {
                    emptyFilter
                } else {
                    grid
                }
            }
            .padding(.bottom, 160)
        }
        .scrollIndicators(.hidden)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom) { actionBar }
        .navigationDestination(item: $detail) { r in
            ShotPagerView(shots: r.shots, startID: r.startID, session: session)
        }
        .sheet(item: $recapShots) { r in RecapBuilderView(shots: r.shots) }
    }

    // MARK: Pieces

    private var filterBar: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(ShotFilter.allCases) { f in
                    let count = f == .all ? session.shots.count : session.shots.filter(f.matches).count
                    Button {
                        withAnimation(.snappy) { filter = f }
                        Haptics.tap()
                    } label: {
                        HStack(spacing: 5) {
                            Text(f.label)
                            Text("\(count)").foregroundStyle(filter == f ? .black.opacity(0.6) : .secondary)
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(filter == f ? Theme.accent : Theme.card, in: Capsule())
                        .foregroundStyle(filter == f ? .black : .primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .safeAreaInset(edge: .bottom, spacing: 6) {
            if filter == .rated {
                HStack(spacing: 4) {
                    Text("At least").font(.caption).foregroundStyle(.secondary)
                    ForEach(1...5, id: \.self) { n in
                        Image(systemName: n <= minStars ? "star.fill" : "star")
                            .foregroundStyle(Theme.star)
                            .onTapGesture { minStars = n; Haptics.tap() }
                    }
                    Spacer()
                    if !session.scanComplete {
                        Text("Reading ratings… \(session.scanned)/\(session.shots.count)").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .font(.subheadline)
                .padding(.horizontal, 16)
            }
        }
    }

    private var grid: some View {
        let cols = Array(repeating: GridItem(.flexible(), spacing: 2), count: columns)
        return LazyVStack(alignment: .leading, spacing: 14, pinnedViews: []) {
            ForEach(days, id: \.0) { day, shots in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(dayTitle(day)).font(.headline)
                        Text("\(shots.count)").font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        if selecting {
                            let allIn = shots.allSatisfy { selection.contains($0.id) }
                            Button(allIn ? "Deselect" : "Select day") {
                                if allIn { shots.forEach { selection.remove($0.id) } } else { shots.forEach { selection.insert($0.id) } }
                            }.font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 16)
                    LazyVGrid(columns: cols, spacing: 2) {
                        ForEach(shots) { shot in
                            ShotCell(shot: shot, selecting: selecting, selected: selection.contains(shot.id))
                                .onTapGesture { tap(shot) }
                                .onLongPressGesture { startSelecting(with: shot) }
                                .onAppear { session.prioritize(shot) }
                        }
                    }
                }
            }
        }
    }

    private var loadingState: some View {
        VStack(spacing: 14) {
            ProgressView()
            switch session.status {
            case .connecting: Text("Opening session…")
            case .cataloging(let p): Text(p > 0 ? "Reading card… \(p)%" : "Reading card…")
            default: Text("No photos on this card")
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private var emptyFilter: some View {
        ContentUnavailableView {
            Label(filter == .new ? "All caught up" : "Nothing here", systemImage: filter == .new ? "checkmark.circle" : "line.3.horizontal.decrease.circle")
        } description: {
            Text(filter == .keepers || filter == .rated
                 ? "Rate (★) or protect (🔒) shots on the camera body — they’ll show up here."
                 : "No shots match this filter.")
        }
        .padding(.top, 40)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { app.disconnect() } label: { Image(systemName: "eject") }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button(selecting ? "Done" : "Select") {
                withAnimation(.snappy) { selecting.toggle(); if !selecting { selection.removeAll() } }
            }
            Menu {
                Picker("Grid", selection: $columns) {
                    Label("Large", systemImage: "square.grid.2x2").tag(2)
                    Label("Medium", systemImage: "square.grid.3x3").tag(3)
                    Label("Small", systemImage: "square.grid.4x3.fill").tag(5)
                }
                Button { recapShots = RecapRoute(shots: recapCandidates) } label: { Label("Make a recap card", systemImage: "rectangle.portrait.on.rectangle.portrait.angled") }
                Divider()
                Button(role: .destructive) { app.disconnect() } label: { Label("Disconnect", systemImage: "eject") }
            } label: { Image(systemName: "ellipsis.circle") }
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
        VStack(spacing: 8) {
            if selecting {
                HStack {
                    Text(selected.isEmpty ? "Tap shots to select" : "\(selected.count) selected · \(ByteCountFormatter.string(fromByteCount: selected.reduce(0) { $0 + $1.totalBytes }, countStyle: .file))")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    if !selected.isEmpty {
                        Button { recapShots = RecapRoute(shots: selected) } label: { Image(systemName: "rectangle.portrait.on.rectangle.portrait.angled") }
                            .buttonStyle(.bordered)
                    }
                    Button {
                        app.importShots(selected)
                        withAnimation { selecting = false; selection.removeAll() }
                    } label: { Label("Import", systemImage: "square.and.arrow.down") }
                    .buttonStyle(.borderedProminent)
                    .disabled(selected.isEmpty)
                }
            } else if !app.importer.isRunning {
                HStack(spacing: 10) {
                    Button { app.importShots(keepersNotImported) } label: {
                        VStack(spacing: 1) {
                            Text("Import ★ & 🔒").font(.subheadline.weight(.semibold))
                            Text(session.scanComplete ? "\(keepersNotImported.count) shots" : "\(keepersNotImported.count) so far…").font(.caption2)
                        }.frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(keepersNotImported.isEmpty)
                    Button { app.importShots(newShots) } label: {
                        VStack(spacing: 1) {
                            Text("Import new").font(.subheadline.weight(.semibold))
                            Text("\(newShots.count) shots").font(.caption2)
                        }.frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(newShots.isEmpty)
                }
                .controlSize(.large)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
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
        withAnimation(.snappy) { selecting = true }
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
    @Environment(AppState.self) private var app
    let session: CameraSession

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: session.kind.symbol)
                .font(.title3)
                .foregroundStyle(Theme.accent)
                .frame(width: 44, height: 44)
                .background(Theme.accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                Text(session.name).font(.headline).lineLimit(1)
                Text(statusLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if session.status != .ready {
                ProgressView().controlSize(.small)
            }
        }
        .padding(12)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var statusLine: String {
        let total = session.shots.count
        let keepers = session.shots.filter(\.isKeeper).count
        let imported = session.shots.filter(\.wasImported).count
        switch session.status {
        case .connecting: return "Connecting via \(session.kind.label)…"
        case .cataloging(let p): return "\(session.kind.label) · cataloging \(p)% · \(total) found"
        case .failed(let m): return m
        case .ready:
            var s = "\(session.kind.label) · \(total) shots · \(keepers) ★/🔒 · \(imported) imported"
            if !session.scanComplete { s += " · reading \(session.scanned)/\(total)" }
            return s
        }
    }
}

struct ShotCell: View {
    let shot: Shot
    let selecting: Bool
    let selected: Bool

    var body: some View {
        Color(white: 0.12)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let t = shot.thumbnail {
                    Image(uiImage: t).resizable().scaledToFill().transition(.opacity)
                } else if shot.isVideo {
                    Image(systemName: "video").foregroundStyle(.secondary)
                }
            }
            .clipped()
            .overlay(alignment: .topLeading) {
                HStack(spacing: 3) {
                    if shot.rating > 0 {
                        Label("\(shot.rating)", systemImage: "star.fill").labelStyle(CompactLabel()).foregroundStyle(Theme.star)
                    }
                    if shot.isLocked { Image(systemName: "lock.fill").foregroundStyle(Theme.lock) }
                }
                .font(.system(size: 10, weight: .bold))
                .padding(4)
                .background(.black.opacity(shot.isKeeper ? 0.55 : 0), in: Capsule())
                .padding(4)
            }
            .overlay(alignment: .bottomLeading) {
                if shot.raw != nil || shot.primary.format.isRAW || shot.isVideo {
                    Text(shot.isVideo ? "VID" : (shot.raw != nil ? "+RAW" : "RAW"))
                        .font(.system(size: 9, weight: .heavy))
                        .padding(.horizontal, 4).padding(.vertical, 2)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                        .padding(4)
                }
            }
            .overlay(alignment: .bottomTrailing) { stateBadge.padding(5) }
            .overlay {
                if selecting {
                    ZStack(alignment: .topTrailing) {
                        Color.black.opacity(selected ? 0.25 : 0)
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(selected ? Theme.accent : .white)
                            .shadow(radius: 2)
                            .padding(5)
                    }
                }
            }
            .contentShape(Rectangle())
            .task(id: shot.id) { await Previews.thumbnail(shot) }
            .animation(.easeOut(duration: 0.2), value: shot.thumbnail != nil)
    }

    @ViewBuilder private var stateBadge: some View {
        switch shot.importState {
        case .queued: Image(systemName: "clock").font(.caption).foregroundStyle(.white).shadow(radius: 2)
        case .importing(let p):
            ProgressView(value: p).progressViewStyle(.circular).controlSize(.mini).tint(Theme.accent)
        case .failed: Image(systemName: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.orange)
        case .done, .none:
            if shot.wasImported {
                Image(systemName: "checkmark.circle.fill").font(.caption).foregroundStyle(.white, .green).shadow(radius: 2)
            }
        }
    }
}

struct CompactLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 1) { configuration.icon; configuration.title }
    }
}
