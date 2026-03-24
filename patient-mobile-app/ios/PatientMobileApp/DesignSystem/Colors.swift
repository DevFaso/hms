import SwiftUI

/// HMS Patient App color palette
extension Color {
    // MARK: - Primary
    static let hmsPrimary = Color(hex: "2563EB")       // Blue 600
    static let hmsPrimaryLight = Color(hex: "3B82F6")   // Blue 500
    static let hmsPrimaryDark = Color(hex: "1D4ED8")    // Blue 700

    // MARK: - Accent
    static let hmsAccent = Color(hex: "10B981")         // Emerald 500
    static let hmsAccentLight = Color(hex: "34D399")    // Emerald 400

    // MARK: - Semantic
    static let hmsSuccess = Color(hex: "22C55E")        // Green 500
    static let hmsWarning = Color(hex: "F59E0B")        // Amber 500
    static let hmsError = Color(hex: "EF4444")          // Red 500
    static let hmsInfo = Color(hex: "3B82F6")           // Blue 500

    // MARK: - Neutral
    static let hmsBackground = Color(hex: "F8FAFC")     // Slate 50
    static let hmsSurface = Color.white
    static let hmsTextPrimary = Color(hex: "1E293B")    // Slate 800
    static let hmsTextSecondary = Color(hex: "64748B")  // Slate 500
    static let hmsTextTertiary = Color(hex: "94A3B8")   // Slate 400
    static let hmsBorder = Color(hex: "E2E8F0")         // Slate 200
    static let hmsDivider = Color(hex: "F1F5F9")        // Slate 100
}

// MARK: - Hex Initializer
extension Color {
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: .alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&int)
        let r, g, b, a: UInt64
        switch cleaned.count {
        case 6:
            (r, g, b, a) = (int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF, 255)
        case 8:
            (r, g, b, a) = (int >> 24 & 0xFF, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (r, g, b, a) = (0, 0, 0, 255)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
