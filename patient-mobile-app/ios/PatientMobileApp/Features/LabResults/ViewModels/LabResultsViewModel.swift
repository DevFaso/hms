import Foundation

@MainActor
final class LabResultsViewModel: ObservableObject {
    @Published var results: [LabResultDto] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func loadResults() async {
        isLoading = true
        errorMessage = nil
        do {
            results = try await api.request(.labResults(limit: 50), type: [LabResultDto].self)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

// MARK: - DTOs

struct LabResultDto: Decodable, Identifiable {
    let id: String
    let testName: String?
    let category: String?
    let orderedBy: String?
    let orderedDate: String?
    let resultDate: String?
    let status: String?
    let results: [LabTestResult]?
    let notes: String?
    let labName: String?

    var statusColor: String {
        switch status?.uppercased() {
        case "COMPLETED": return "hmsSuccess"
        case "PENDING":   return "hmsWarning"
        case "CANCELLED": return "hmsError"
        default:          return "hmsTextSecondary"
        }
    }
}

struct LabTestResult: Decodable, Identifiable {
    var id: String { parameterName ?? UUID().uuidString }
    let parameterName: String?
    let value: String?
    let unit: String?
    let referenceRange: String?
    let isAbnormal: Bool?
}
