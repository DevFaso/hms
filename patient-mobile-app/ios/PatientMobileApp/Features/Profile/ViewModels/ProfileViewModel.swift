import Foundation

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published var profile: PatientProfile?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            profile = try await api.request(.profile, type: PatientProfile.self)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct PatientProfile: Decodable {
    let id: String
    let firstName: String?
    let lastName: String?
    let mrn: String?
    let dateOfBirth: String?
    let gender: String?
    let bloodType: String?
    let email: String?
    let phone: String?
    let address: String?
    let emergencyContactName: String?
    let emergencyContactPhone: String?
    let emergencyContactRelation: String?

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }
}
