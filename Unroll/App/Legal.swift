import Foundation

/// Store-required links. Privacy and support pages live in docs/ and are served by GitHub Pages.
enum Legal {
    static let privacy = URL(string: "https://tannguyenwatt.github.io/unroll/privacy.html")!
    static let support = URL(string: "https://tannguyenwatt.github.io/unroll/support.html")!
    /// Apple's standard licence agreement; App Store Connect uses it when no custom EULA is set.
    static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
}
