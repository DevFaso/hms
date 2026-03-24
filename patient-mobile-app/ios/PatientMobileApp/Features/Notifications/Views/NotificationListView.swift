import SwiftUI

struct NotificationListView: View {
    @StateObject private var viewModel = NotificationViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading notifications...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.load() } }
                } else if viewModel.notifications.isEmpty {
                    HMSEmptyState(icon: "bell.slash", title: "No Notifications", message: "You're all caught up!")
                } else {
                    List {
                        ForEach(viewModel.notifications) { notification in
                            NotificationRow(notification: notification) {
                                if !(notification.read ?? true) {
                                    Task { await viewModel.markAsRead(id: notification.id) }
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Notifications")
            .toolbar {
                if viewModel.unreadCount > 0 {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text("\(viewModel.unreadCount) unread")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextSecondary)
                    }
                }
            }
            .refreshable { await viewModel.load() }
            .task { await viewModel.load() }
        }
    }
}

// MARK: - Notification Row

private struct NotificationRow: View {
    let notification: NotificationDTO
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: notification.icon)
                    .font(.system(size: 20))
                    .foregroundColor(iconColor)
                    .frame(width: 36, height: 36)
                    .background(iconColor.opacity(0.12))
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(notification.title ?? "Notification")
                            .font(isUnread ? .hmsBodyMedium : .hmsBody)
                            .foregroundColor(.hmsTextPrimary)
                        Spacer()
                        if let time = notification.createdAt {
                            Text(formatTimeAgo(time))
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextTertiary)
                        }
                    }
                    if let message = notification.message {
                        Text(message)
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextSecondary)
                            .lineLimit(2)
                    }
                }

                if isUnread {
                    Circle()
                        .fill(Color.hmsPrimary)
                        .frame(width: 8, height: 8)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .listRowBackground(isUnread ? Color.hmsPrimary.opacity(0.04) : Color.clear)
    }

    private var isUnread: Bool { !(notification.read ?? true) }

    private var iconColor: Color {
        switch notification.type?.uppercased() {
        case "APPOINTMENT": return .hmsPrimary
        case "LAB_RESULT": return .hmsWarning
        case "PRESCRIPTION", "MEDICATION": return .hmsAccent
        case "BILLING": return .hmsInfo
        case "MESSAGE": return .hmsSuccess
        default: return .hmsTextSecondary
        }
    }

    private func formatTimeAgo(_ dateString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: dateString) else {
            // Try without fractional seconds
            formatter.formatOptions = [.withInternetDateTime]
            guard let date = formatter.date(from: dateString) else { return dateString }
            return relativeTime(from: date)
        }
        return relativeTime(from: date)
    }

    private func relativeTime(from date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
