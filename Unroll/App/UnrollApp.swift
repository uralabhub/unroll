import SwiftUI

@main
struct UnrollApp: App {
    @State private var app = AppState()

    init() {
        // Navigation and toolbar titles in Nunito too (SwiftUI's .font doesn't reach them).
        let nav = UINavigationBar.appearance()
        nav.titleTextAttributes = [.font: Montserrat.uiFont(17, .heavy)]
        nav.largeTitleTextAttributes = [.font: Montserrat.uiFont(34, .heavy)]
        UIBarButtonItem.appearance().setTitleTextAttributes([.font: Montserrat.uiFont(17, .bold)], for: .normal)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .font(.sans(.body))
                .environment(app)
                .tint(Mono.ink)
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
