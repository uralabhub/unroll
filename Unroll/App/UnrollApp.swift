import SwiftUI

@main
struct UnrollApp: App {
    @State private var app = AppState()

    init() {
        // Navigation titles in Nunito, matching the rest of the type.
        let nav = UINavigationBar.appearance()
        nav.titleTextAttributes = [.font: Mono.uiSans(17, weight: .semibold)]
        nav.largeTitleTextAttributes = [.font: Mono.uiSans(32, weight: .bold)]
        UIBarButtonItem.appearance().setTitleTextAttributes([.font: Mono.uiSans(17)], for: .normal)
    }
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .font(Mono.sans())
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
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.92, anchor: .top).combined(with: .opacity),
                            removal: .scale(scale: 1.04).combined(with: .opacity)))
                } else {
                    HomeView()
                        .transition(.asymmetric(
                            insertion: .scale(scale: 1.04).combined(with: .opacity),
                            removal: .scale(scale: 0.94, anchor: .top).combined(with: .opacity)))
                }
            }
            .animation(Motion.glide, value: app.session?.id)
        }
        .overlay(alignment: .bottom) { ImportHUD().padding(.bottom, 8) }
        .overlay(alignment: .top) { ToastView() }
        .sheet(isPresented: $app.showPaywall) { PaywallView() }
    }
}
