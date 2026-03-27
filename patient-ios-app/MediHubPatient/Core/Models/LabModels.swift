import Foundation

// MARK: - Lab Result Models

struct LabResultDTO: Codable, Identifiable {
    let id: String?
    let testName: String?
    let result: String?
    let referenceRange: String?
    let status: String?
    let collectedDate: String?
    let isAbnormal: Bool?

    // Legacy compat
    let testCode: String?
    let unit: String?
    let orderedDate: String?
    let resultDate: String?
    let orderedBy: String?
    let labName: String?
    let critical: Bool?
    let notes: String?

    var abnormal: Bool { isAbnormal ?? false }
    var isCritical: Bool { critical ?? false }

    var statusDisplay: String {
        switch status?.uppercased() {
        case "FINAL":     return "Final"
        case "PENDING":   return "Pending"
        case "CANCELLED": return "Cancelled"
        default:          return status?.capitalized ?? "Unknown"
        }
    }
}
