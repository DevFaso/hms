import SwiftUI
import Combine

/// Shared manager that keeps the current user's profile‐image URL
/// in sync across the entire app (tab bar, side menu, profile view, etc.).
@MainActor
final class ProfileImageManager: ObservableObject {
    static let shared = ProfileImageManager()
    private init() {}

    @Published var profileImageUrl: String?

    /// Call after login / profile fetch to broadcast the URL.
    func update(url: String?) {
        profileImageUrl = url
    }

    /// Resolved URL ready for AsyncImage.
    var resolvedURL: URL? {
        guard let url = profileImageUrl, !url.isEmpty else { return nil }
        if url.hasPrefix("http") { return URL(string: url) }
        let base = AppEnvironment.baseURL.replacingOccurrences(of: "/api", with: "")
        return URL(string: base + url)
    }
}
