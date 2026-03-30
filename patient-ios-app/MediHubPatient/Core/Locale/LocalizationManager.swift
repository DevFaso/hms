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

    static let supportedLanguages: [(code: String, name: String)] = [
        ("en", "English"),
        ("fr", "Français")
    ]

    private init() {
        let saved = UserDefaults.standard.string(forKey: "app_language") ?? "en"
        self.currentLanguage = saved
        self.bundle = Self.loadBundle(for: saved)
    }

    private static func loadBundle(for languageCode: String) -> Bundle {
        guard let path = Bundle.main.path(forResource: languageCode, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            return Bundle.main
        }
        return bundle
    }

    func localizedString(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: nil, table: nil)
    }
}

// Convenience extension for SwiftUI Text
extension String {
    var localized: String {
        LocalizationManager.shared.localizedString(self)
    }
}
