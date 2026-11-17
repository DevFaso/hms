import Foundation
import SwiftUI

final class LocalizationManager: ObservableObject {
    static let shared = LocalizationManager()

    @Published var currentLanguage: String {
        didSet {
            UserDefaults.standard.set(currentLanguage, forKey: "app_language")
            bundle = Self.loadBundle(for: currentLanguage)
        }
    }

    private(set) var bundle: Bundle

    /// Each language named in itself, as a picker should.
    static let supportedLanguages: [(code: String, name: String)] = [
        ("en", "English"),
        ("fr", "Français"),
        ("es", "Español"),
    ]

    private init() {
        let saved = UserDefaults.standard.string(forKey: "app_language") ?? "en"
        // A saved code this build no longer ships falls back to English
        // rather than to a bundle that does not exist.
        let language = Self.supportedLanguages.contains { $0.code == saved } ? saved : "en"
        currentLanguage = language
        bundle = Self.loadBundle(for: language)
    }

    private static func loadBundle(for languageCode: String) -> Bundle {
        guard let path = Bundle.main.path(forResource: languageCode, ofType: "lproj"),
              let bundle = Bundle(path: path)
        else {
            return Bundle.main
        }
        return bundle
    }

    func localizedString(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    func setLanguage(_ code: String) {
        currentLanguage = code
    }
}

/// Convenience extension for SwiftUI Text
extension String {
    var localized: String {
        LocalizationManager.shared.localizedString(self)
    }
}
