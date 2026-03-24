import Foundation

/// Localization manager for multilingual support
/// Uses .strings files and NSLocalizedString under the hood
final class LocalizationManager: ObservableObject {
    static let shared = LocalizationManager()

    @Published var currentLanguage: String {
        didSet {
            UserDefaults.standard.set(currentLanguage, forKey: "app_language")
            UserDefaults.standard.set([currentLanguage], forKey: "AppleLanguages")
            bundle = Bundle.main.path(forResource: currentLanguage, ofType: "lproj")
                .flatMap { Bundle(path: $0) } ?? Bundle.main
        }
    }

    private(set) var bundle: Bundle

    static let supportedLanguages: [(code: String, name: String)] = [
        ("en", "English"),
        ("fr", "Français"),
        ("es", "Español"),
        ("pt", "Português"),
        ("sw", "Kiswahili"),
        ("ar", "العربية"),
        ("zh", "中文")
    ]

    private init() {
        let saved = UserDefaults.standard.string(forKey: "app_language")
            ?? Locale.current.language.languageCode?.identifier
            ?? "en"
        currentLanguage = saved
        bundle = Bundle.main.path(forResource: saved, ofType: "lproj")
            .flatMap { Bundle(path: $0) } ?? Bundle.main
    }

    /// Lookup a localized string
    func localized(_ key: String, comment: String = "") -> String {
        NSLocalizedString(key, bundle: bundle, comment: comment)
    }
}

// MARK: - String Extension

extension String {
    /// Convenience for localization: "key".localized
    var localized: String {
        LocalizationManager.shared.localized(self)
    }
}
