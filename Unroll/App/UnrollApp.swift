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
                // A photo tool: dark chrome so the pictures carry the colour (Photos, Lightroom, Halide).
                .preferredColorScheme(.dark)
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
            ZStack {
                if let session = app.session {
                    LibraryView(session: session)
                        .id(session.id)
                        .transition(.opacity)
                } else {
                    HomeView()
                        .transition(.opacity)
                }
            }
            .animation(Motion.glide, value: app.session?.id)
        }
        .overlay(alignment: .bottom) { ImportHUD().padding(.bottom, 8) }
        .overlay(alignment: .bottom) { ToastView().padding(.bottom, 96) }
        .sheet(isPresented: $app.showPaywall) { PaywallView() }
        .alert("Photos access is off", isPresented: $app.photosDenied) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("Unroll saves your shots to Photos. Allow access in Settings, then import again.")
        }
    }
}
