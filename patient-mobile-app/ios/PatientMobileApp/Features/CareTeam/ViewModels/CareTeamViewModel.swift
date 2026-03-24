import Foundation

@MainActor
final class CareTeamViewModel: ObservableObject {
    @Published var members: [CareTeamMemberDto] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func loadCareTeam() async {
        isLoading = true
        errorMessage = nil
        do {
            members = try await api.request(.careTeam, type: [CareTeamMemberDto].self)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

// MARK: - DTOs

struct CareTeamMemberDto: Decodable, Identifiable {
    let id: String
    let firstName: String?
    let lastName: String?
    let role: String?
    let specialty: String?
    let department: String?
    let phone: String?
    let email: String?
    let photoUrl: String?
    let hospitalName: String?

    var displayName: String {
        let name = [firstName, lastName].compactMap { $0 }.joined(separator: " ")
        return name.isEmpty ? "Unknown Provider" : name
    }

    var initials: String {
        let f = firstName?.prefix(1) ?? ""
        let l = lastName?.prefix(1) ?? ""
        return "\(f)\(l)".uppercased()
    }
}
