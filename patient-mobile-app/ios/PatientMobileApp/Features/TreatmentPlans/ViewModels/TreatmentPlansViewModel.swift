import Foundation

// MARK: - DTOs

struct TreatmentPlanDto: Codable, Identifiable {
    let id: String
    let planName: String?
    let description: String?
    let diagnosis: String?
    let status: String?
    let startDate: String?
    let endDate: String?
    let createdByName: String?
    let createdBySpecialty: String?
    let hospitalName: String?
    let goals: [TreatmentGoalDto]?
    let activities: [TreatmentActivityDto]?
    let notes: String?
    let createdAt: String?
}

struct TreatmentGoalDto: Codable, Identifiable {
    let id: String
    let goalDescription: String?
    let targetDate: String?
    let status: String?
    let progressPercentage: Int?
}

struct TreatmentActivityDto: Codable, Identifiable {
    let id: String
    let activityType: String?
    let description: String?
    let frequency: String?
    let duration: String?
    let status: String?
    let assignedTo: String?
}

struct TreatmentPlanPage: Codable {
    let content: [TreatmentPlanDto]
    let totalPages: Int
    let totalElements: Int
    let number: Int
}

// MARK: - ViewModel

@MainActor
final class TreatmentPlansViewModel: ObservableObject {
    @Published var plans: [TreatmentPlanDto] = []
    @Published var isLoading = false
    @Published var error: String?
    @Published var hasMore = true

    private var currentPage = 0
    private let pageSize = 20
    private let cache = CacheManager.shared

    func loadPlans(reset: Bool = false) async {
        if reset {
            currentPage = 0
            hasMore = true
            plans = []
        }
        guard hasMore, !isLoading else { return }
        isLoading = true
        error = nil

        do {
            let page: TreatmentPlanPage = try await APIClient.shared.request(
                .treatmentPlans(page: currentPage, size: pageSize),
                type: TreatmentPlanPage.self
            )
            if reset { plans = page.content } else { plans.append(contentsOf: page.content) }
            hasMore = currentPage < page.totalPages - 1
            currentPage += 1
            cache.store(plans, forKey: "treatment_plans")
        } catch {
            if plans.isEmpty, let cached = cache.retrieve(forKey: "treatment_plans", as: [TreatmentPlanDto].self) {
                plans = cached
            }
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    var statusColor: (String?) -> String {
        { status in
            switch status?.lowercased() {
            case "active": return "green"
            case "completed": return "blue"
            case "cancelled": return "red"
            case "on_hold", "on hold": return "orange"
            default: return "gray"
            }
        }
    }
}
