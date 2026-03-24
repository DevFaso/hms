import Foundation

@MainActor
final class ConsentsViewModel: ObservableObject {
    @Published var consents: [ConsentDto] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isGranting = false
    @Published var grantSuccess = false

    private let api = APIClient.shared

    func loadConsents() async {
        isLoading = true
        errorMessage = nil
        do {
            consents = try await api.request(.consents(page: 0, size: 50), type: [ConsentDto].self)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func grantConsent(_ request: GrantConsentRequest) async {
        isGranting = true
        errorMessage = nil
        do {
            try await api.request(.grantConsent, body: request)
            grantSuccess = true
            await loadConsents()
        } catch {
            errorMessage = error.localizedDescription
        }
        isGranting = false
    }

    func revokeConsent(fromHospitalId: String, toHospitalId: String) async {
        do {
            try await api.request(.revokeConsent(fromHospitalId: fromHospitalId, toHospitalId: toHospitalId))
            await loadConsents()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - DTOs

struct ConsentDto: Decodable, Identifiable {
    let id: String
    let fromHospitalId: String?
    let fromHospitalName: String?
    let toHospitalId: String?
    let toHospitalName: String?
    let consentType: String?
    let status: String?
    let grantedDate: String?
    let expiryDate: String?
    let notes: String?

    var isActive: Bool { status?.uppercased() == "ACTIVE" }
}

struct GrantConsentRequest: Encodable {
    let fromHospitalId: String
    let toHospitalId: String
    let consentType: String
    let notes: String?
}
