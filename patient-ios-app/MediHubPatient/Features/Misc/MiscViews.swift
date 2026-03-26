import SwiftUI

struct NotificationsView: View {
    @StateObject private var vm = NotificationsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.notifications.isEmpty { ProgressView("Loading notifications…") }
                else if vm.notifications.isEmpty {
                    ContentUnavailableView("No Notifications", systemImage: "bell.slash.fill",
                        description: Text("You're all caught up."))
                } else {
                    List(vm.notifications) { notif in
                        NotificationRow(notification: notif)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Notifications")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

struct NotificationRow: View {
    let notification: NotificationDTO
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle()
                .fill(notification.isRead ? Color.clear : Color.accentColor)
                .frame(width: 8, height: 8)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 4) {
                Text(notification.title ?? "Notification")
                    .font(.subheadline).bold()
                    .foregroundColor(notification.isRead ? .secondary : .primary)
                Text(notification.message ?? "")
                    .font(.caption).foregroundColor(.secondary).lineLimit(2)
                Text(notification.createdAt ?? "").font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class NotificationsViewModel: ObservableObject {
    @Published var notifications: [NotificationDTO] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        notifications = (try? await APIClient.shared.get(APIEndpoints.notifications)) ?? []
        isLoading = false
    }
}

// MARK: - Documents

struct DocumentsView: View {
    @StateObject private var vm = DocumentsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.documents.isEmpty { ProgressView("Loading documents…") }
                else if vm.documents.isEmpty {
                    ContentUnavailableView("No Documents", systemImage: "doc.fill",
                        description: Text("No documents on file."))
                } else {
                    List(vm.documents) { doc in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(doc.fileName ?? "Document").font(.headline)
                            if let cat = doc.category { Text(cat).font(.caption).foregroundColor(.secondary) }
                            if let date = doc.uploadedAt { Text(date).font(.caption2).foregroundColor(.secondary) }
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Documents")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

@MainActor
final class DocumentsViewModel: ObservableObject {
    @Published var documents: [DocumentDTO] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        let page: PageDTO<DocumentDTO>? = try? await APIClient.shared.get(
            APIEndpoints.documents,
            queryItems: [URLQueryItem(name: "page", value: "0"),
                         URLQueryItem(name: "size", value: "50")]
        )
        if let content = page?.content {
            documents = content
        } else {
            documents = (try? await APIClient.shared.get(APIEndpoints.documents)) ?? []
        }
        isLoading = false
    }
}

// MARK: - Health Records

struct HealthRecordsView: View {
    @StateObject private var vm = HealthRecordsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading { ProgressView("Loading health records…") }
                else if let summary = vm.summary {
                    List {
                        Section("Active Diagnoses") {
                            ForEach(summary.activeDiagnoses ?? [], id: \.self) { Text($0) }
                        }
                        Section("Current Medications") {
                            ForEach(summary.currentMedications ?? [], id: \.self) { Text($0) }
                        }
                        Section("Allergies") {
                            ForEach(summary.allergies ?? [], id: \.self) {
                                Label($0, systemImage: "exclamationmark.triangle.fill").foregroundColor(.red)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                } else {
                    ContentUnavailableView("No Health Records", systemImage: "heart.text.square",
                        description: Text("No health records available."))
                }
            }
            .navigationTitle("Health Records")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

@MainActor
final class HealthRecordsViewModel: ObservableObject {
    @Published var summary: HealthSummaryDTO?
    @Published var isLoading = false

    func load() async {
        isLoading = true
        summary = try? await APIClient.shared.get(APIEndpoints.healthSummary)
        isLoading = false
    }
}

// MARK: - Sharing & Privacy

struct SharingPrivacyView: View {
    @StateObject private var vm = SharingPrivacyViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading { ProgressView("Loading…") }
                else {
                    List {
                        Section("Consents Granted") {
                            if vm.consents.isEmpty {
                                Text("No active consents.").foregroundColor(.secondary)
                            } else {
                                ForEach(vm.consents) { consent in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(consent.toHospitalName ?? "Hospital").font(.headline)
                                        if let granted = consent.grantedAt {
                                            Text("Granted: \(granted)").font(.caption).foregroundColor(.secondary)
                                        }
                                        if let expires = consent.expiresAt {
                                            Text("Expires: \(expires)").font(.caption2).foregroundColor(.secondary)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                        }
                        Section("Access Log") {
                            if vm.accessLog.isEmpty {
                                Text("No access records.").foregroundColor(.secondary)
                            } else {
                                ForEach(vm.accessLog) { log in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(log.accessedBy ?? "Unknown").font(.subheadline)
                                        Text("\(log.action ?? "") · \(log.hospitalName ?? "")")
                                            .font(.caption).foregroundColor(.secondary)
                                        Text(log.accessedAt ?? "").font(.caption2).foregroundColor(.secondary)
                                    }
                                    .padding(.vertical, 2)
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Sharing & Privacy")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

@MainActor
final class SharingPrivacyViewModel: ObservableObject {
    @Published var consents: [ConsentDTO] = []
    @Published var accessLog: [AccessLogDTO] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                let page: PageDTO<ConsentDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.consents,
                    queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "20")]
                )
                self.consents = page?.content ?? []
            }
            group.addTask { @MainActor in
                let page: PageDTO<AccessLogDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.accessLog,
                    queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "20")]
                )
                self.accessLog = page?.content ?? []
            }
        }
        isLoading = false
    }
}
