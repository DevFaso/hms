import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var healthSummary: HealthSummary?
    @Published var recentActivity: [ActivityItem] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            healthSummary = try await api.request(
                .healthSummary,
                type: HealthSummary.self
            )
            // Build activity items from summary data
            buildRecentActivity()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func buildRecentActivity() {
        guard let summary = healthSummary else { return }
        var items: [ActivityItem] = []

        if summary.upcomingAppointments > 0 {
            items.append(ActivityItem(
                id: "apt",
                icon: "calendar",
                title: "Upcoming Appointment",
                subtitle: "\(summary.upcomingAppointments) scheduled",
                timeAgo: "Soon"
            ))
        }
        if summary.pendingLabResults > 0 {
            items.append(ActivityItem(
                id: "lab",
                icon: "flask",
                title: "Lab Results Pending",
                subtitle: "\(summary.pendingLabResults) awaiting results",
                timeAgo: "Today"
            ))
        }
        if summary.unreadMessages > 0 {
            items.append(ActivityItem(
                id: "msg",
                icon: "message.fill",
                title: "New Messages",
                subtitle: "\(summary.unreadMessages) unread",
                timeAgo: "New"
            ))
        }
        recentActivity = items
    }
}

// MARK: - Models

struct HealthSummary: Decodable {
    let upcomingAppointments: Int
    let activeMedications: Int
    let pendingLabResults: Int
    let unreadMessages: Int
}

struct ActivityItem: Identifiable {
    let id: String
    let icon: String
    let title: String
    let subtitle: String
    let timeAgo: String
}
