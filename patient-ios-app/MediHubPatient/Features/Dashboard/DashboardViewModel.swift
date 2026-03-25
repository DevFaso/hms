import Foundation

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var healthSummary: HealthSummaryDTO?
    @Published var appointments: [AppointmentDTO] = []
    @Published var medications: [MedicationDTO] = []
    @Published var labResults: [LabResultDTO] = []
    @Published var notifications: [NotificationDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    var unreadNotificationCount: Int {
        notifications.filter { !$0.isRead }.count
    }

    var totalDue: Double {
        // populated by BillingViewModel — exposed here for dashboard badge
        0
    }

    var upcomingAppointments: [AppointmentDTO] {
        appointments.filter {
            let s = $0.status?.uppercased() ?? ""
            return s != "CANCELLED" && s != "COMPLETED" && s != "NO_SHOW"
        }.prefix(3).map { $0 }
    }

    func loadAll() async {
        isLoading = true
        errorMessage = nil
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.loadHealthSummary() }
            group.addTask { await self.loadAppointments() }
            group.addTask { await self.loadMedications() }
            group.addTask { await self.loadLabResults() }
            group.addTask { await self.loadNotifications() }
        }
        isLoading = false
    }

    private func loadHealthSummary() async {
        healthSummary = try? await APIClient.shared.get(APIEndpoints.healthSummary)
    }

    private func loadAppointments() async {
        appointments = (try? await APIClient.shared.get(APIEndpoints.appointments)) ?? []
    }

    private func loadMedications() async {
        medications = (try? await APIClient.shared.get(
            APIEndpoints.medications,
            queryItems: [URLQueryItem(name: "limit", value: "5")]
        )) ?? []
    }

    private func loadLabResults() async {
        labResults = (try? await APIClient.shared.get(
            APIEndpoints.labResults,
            queryItems: [URLQueryItem(name: "limit", value: "5")]
        )) ?? []
    }

    private func loadNotifications() async {
        notifications = (try? await APIClient.shared.get(APIEndpoints.notifications)) ?? []
    }
}
