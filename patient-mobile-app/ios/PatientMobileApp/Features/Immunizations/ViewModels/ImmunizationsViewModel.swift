import Foundation

@MainActor
final class ImmunizationsViewModel: ObservableObject {
    @Published var immunizations: [ImmunizationDto] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func loadImmunizations() async {
        isLoading = true
        errorMessage = nil
        do {
            immunizations = try await api.request(.immunizations, type: [ImmunizationDto].self)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

// MARK: - DTOs

struct ImmunizationDto: Decodable, Identifiable {
    let id: String
    let vaccineName: String?
    let vaccineCode: String?
    let doseNumber: Int?
    let totalDoses: Int?
    let administeredDate: String?
    let expirationDate: String?
    let administeredBy: String?
    let site: String?
    let lotNumber: String?
    let manufacturer: String?
    let status: String?
    let notes: String?
}
