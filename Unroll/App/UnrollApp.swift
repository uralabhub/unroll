import SwiftUI

@main
struct UnrollApp: App {
    @State private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

enum Theme {
    static let accent = Color(red: 1.0, green: 0.66, blue: 0.25)
    static let card = Color(white: 0.11)
    static let cardHi = Color(white: 0.16)
    static let star = Color(red: 1.0, green: 0.8, blue: 0.2)
    static let lock = Color(red: 0.45, green: 0.75, blue: 1.0)
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
