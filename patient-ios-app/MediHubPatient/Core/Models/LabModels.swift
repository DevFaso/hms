import Foundation

// MARK: - Lab Result Models

struct LabResultDTO: Codable, Identifiable {
    let id: Int?
    let testName: String?
    let testCode: String?
    let result: String?
    let unit: String?
    let referenceRange: String?
    let status: String?
    let orderedDate: String?
    let resultDate: String?
    let orderedBy: String?
    let labName: String?
    let abnormal: Bool?
    let critical: Bool?
    let notes: String?

    var isAbnormal: Bool { abnormal ?? false }
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
