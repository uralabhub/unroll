import SwiftUI

@main
struct UnrollApp: App {
    @State private var app = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .tint(Mono.ink)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { app.importer.resumeIfPaused() }
        }
    }
}

struct RootView: View {
    @Environment(AppState.self) private var app

    var body: some View {
        @Bindable var app = app
        NavigationStack {
            Group {
                if let session = app.session {
                    LibraryView(session: session)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    HomeView()
                        .transition(.opacity)
                }
            }
            .animation(.snappy, value: app.session?.id)
        }
        .overlay(alignment: .bottom) { ImportHUD().padding(.bottom, 8) }
        .overlay(alignment: .top) { ToastView() }
        .sheet(isPresented: $app.showPaywall) { PaywallView() }
    }
}
