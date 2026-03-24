import Foundation

// MARK: - DTOs

struct ConsultationDto: Codable, Identifiable {
    let id: String
    let consultationType: String?
    let reason: String?
    let priority: String?
    let status: String?
    let requestedDate: String?
    let scheduledDate: String?
    let completedDate: String?
    let requestingDoctorName: String?
    let requestingDepartment: String?
    let consultantDoctorName: String?
    let consultantDepartment: String?
    let consultantSpecialty: String?
    let hospitalName: String?
    let findings: String?
    let recommendations: String?
    let diagnosis: String?
    let notes: String?
    let followUpRequired: Bool?
    let followUpDate: String?
    let createdAt: String?
}

// MARK: - ViewModel

@MainActor
final class ConsultationsViewModel: ObservableObject {
    @Published var consultations: [ConsultationDto] = []
    @Published var isLoading = false
    @Published var error: String?

    private let cache = CacheManager.shared

    func loadConsultations() async {
        isLoading = true
        error = nil

        do {
            let result: [ConsultationDto] = try await APIClient.shared.request(
                .consultations,
                type: [ConsultationDto].self
            )
            consultations = result
            cache.store(result, forKey: "consultations")
        } catch {
            if consultations.isEmpty, let cached = cache.retrieve(forKey: "consultations", as: [ConsultationDto].self) {
                consultations = cached
            }
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}
