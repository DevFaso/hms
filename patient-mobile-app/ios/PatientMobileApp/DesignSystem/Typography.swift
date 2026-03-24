import SwiftUI

/// HMS typography scale
extension Font {
    // MARK: - Headings
    static let hmsTitle = Font.system(size: 28, weight: .bold, design: .rounded)
    static let hmsHeadline = Font.system(size: 22, weight: .semibold, design: .rounded)
    static let hmsSubheadline = Font.system(size: 18, weight: .semibold)

    // MARK: - Body
    static let hmsBody = Font.system(size: 16, weight: .regular)
    static let hmsBodyMedium = Font.system(size: 16, weight: .medium)
    static let hmsBodyBold = Font.system(size: 16, weight: .bold)

    // MARK: - Small
    static let hmsCaption = Font.system(size: 13, weight: .regular)
    static let hmsCaptionMedium = Font.system(size: 13, weight: .medium)

    // MARK: - Extra
    static let hmsOverline = Font.system(size: 11, weight: .semibold).uppercaseSmallCaps()
    static let hmsLargeNumber = Font.system(size: 36, weight: .bold, design: .rounded)
}
