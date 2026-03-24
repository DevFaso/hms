import Foundation

// MARK: - DTOs

struct ReferralDto: Codable, Identifiable {
    let id: String
    let referralNumber: String?
    let referralType: String?
    let referralReason: String?
    let clinicalNotes: String?
    let urgency: String?
    let status: String?
    let referringDoctorName: String?
    let referringDepartment: String?
    let referringHospital: String?
    let referredToDoctorName: String?
    let referredToDepartment: String?
    let referredToHospital: String?
    let referralDate: String?
    let expiryDate: String?
    let appointmentDate: String?
    let diagnosisCode: String?
    let diagnosisDescription: String?
    let completedDate: String?
    let completionNotes: String?
    let createdAt: String?
}

// MARK: - ViewModel

@MainActor
final class ReferralsViewModel: ObservableObject {
    @Published var referrals: [ReferralDto] = []
    @Published var isLoading = false
    @Published var error: String?

    private let cache = CacheManager.shared

    func loadReferrals() async {
        isLoading = true
        error = nil

        do {
            let result: [ReferralDto] = try await APIClient.shared.request(
                .referrals,
                type: [ReferralDto].self
            )
            referrals = result
            cache.store(result, forKey: "referrals")
        } catch {
            if referrals.isEmpty, let cached = cache.retrieve(forKey: "referrals", as: [ReferralDto].self) {
                referrals = cached
            }
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    var urgencyIcon: (String?) -> String {
        { urgency in
            switch urgency?.lowercased() {
            case "urgent", "emergency": return "exclamationmark.triangle.fill"
            case "high": return "exclamationmark.circle.fill"
            case "routine", "normal": return "clock"
            default: return "arrow.right.circle"
            }
        }
    }
}
