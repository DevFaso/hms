import Foundation

@MainActor
final class NotificationViewModel: ObservableObject {
    @Published var notifications: [NotificationDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    var unreadCount: Int {
        notifications.filter { !($0.read ?? true) }.count
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            notifications = try await api.request(.notifications, type: [NotificationDTO].self)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func markAsRead(id: String) async {
        do {
            try await api.request(.markNotificationRead(id: id))
            if let idx = notifications.firstIndex(where: { $0.id == id }) {
                notifications[idx] = notifications[idx].asRead()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - DTOs

struct NotificationDTO: Decodable, Identifiable {
    let id: String
    let title: String?
    let message: String?
    let type: String?
    let read: Bool?
    let createdAt: String?
    let relatedEntityType: String?
    let relatedEntityId: String?

    func asRead() -> NotificationDTO {
        NotificationDTO(
            id: id, title: title, message: message, type: type,
            read: true, createdAt: createdAt,
            relatedEntityType: relatedEntityType, relatedEntityId: relatedEntityId
        )
    }

    var icon: String {
        switch type?.uppercased() {
        case "APPOINTMENT": return "calendar"
        case "LAB_RESULT": return "flask"
        case "PRESCRIPTION", "MEDICATION": return "pill"
        case "BILLING": return "creditcard"
        case "MESSAGE": return "message"
        default: return "bell"
        }
    }

    var iconColor: String {
        switch type?.uppercased() {
        case "APPOINTMENT": return "hmsPrimary"
        case "LAB_RESULT": return "hmsWarning"
        case "PRESCRIPTION", "MEDICATION": return "hmsAccent"
        case "BILLING": return "hmsInfo"
        case "MESSAGE": return "hmsSuccess"
        default: return "hmsTextSecondary"
        }
    }
}
