import SwiftUI

struct NotificationsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = NotificationsViewModel()

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.load() }
        } else {
            content
                .task { await vm.load() }
        }
    }

    private var content: some View {
        Group {
            if vm.isLoading && vm.notifications.isEmpty { ProgressView("loading".localized) }
            else if vm.notifications.isEmpty {
                ContentUnavailableView("no_notifications".localized, systemImage: "bell.slash.fill",
                    description: Text("no_notifications_desc".localized))
            } else {
                List(vm.notifications) { notif in
                    NotificationRow(notification: notif)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("notifications".localized)
        .refreshable { await vm.load() }
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
    var embeddedInNav: Bool = true
    @StateObject private var vm = DocumentsViewModel()

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.load() }
        } else {
            content
                .task { await vm.load() }
        }
    }

    private var content: some View {
        Group {
            if vm.isLoading && vm.documents.isEmpty { ProgressView("loading".localized) }
            else if vm.documents.isEmpty {
                ContentUnavailableView("no_documents".localized, systemImage: "doc.fill",
                    description: Text("no_documents_desc".localized))
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
        .navigationTitle("documents".localized)
        .refreshable { await vm.load() }
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

// MARK: - Health Records (Tabbed — matches Angular my-records.ts)

struct HealthRecordsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = HealthRecordsViewModel()
    @State private var selectedTab = 0

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.loadAll() }
        } else {
            content
                .task { await vm.loadAll() }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            // Tab picker
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    TabChip(title: "Overview", isSelected: selectedTab == 0) { selectedTab = 0 }
                    TabChip(title: "Encounters", isSelected: selectedTab == 1) { selectedTab = 1 }
                    TabChip(title: "Labs", isSelected: selectedTab == 2) { selectedTab = 2 }
                    TabChip(title: "Medications", isSelected: selectedTab == 3) { selectedTab = 3 }
                    TabChip(title: "Immunizations", isSelected: selectedTab == 4) { selectedTab = 4 }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 8)

            if vm.isLoading {
                ProgressView("Loading…").padding()
            } else {
                switch selectedTab {
                case 0: overviewTab
                case 1: encountersTab
                case 2: labsTab
                case 3: medicationsTab
                case 4: immunizationsTab
                default: overviewTab
                }
            }
        }
        .navigationTitle("health_records".localized)
        .refreshable { await vm.loadAll() }
    }

    // MARK: Overview
    private var overviewTab: some View {
        List {
            if let profile = vm.summary?.profile {
                Section("Personal Information") {
                    if let dob = profile.dateOfBirth { HStack { Text("Date of Birth").foregroundColor(.secondary); Spacer(); Text(dob) } }
                    if let gender = profile.gender { HStack { Text("Gender").foregroundColor(.secondary); Spacer(); Text(gender) } }
                    if let blood = profile.bloodType { HStack { Text("Blood Type").foregroundColor(.secondary); Spacer(); Text(blood) } }
                }
            }
            Section("Allergies") {
                let allergies = vm.summary?.allergies ?? []
                if allergies.isEmpty {
                    Text("No known allergies").foregroundColor(.secondary)
                } else {
                    ForEach(allergies, id: \.self) {
                        Label($0, systemImage: "exclamationmark.triangle.fill").foregroundColor(.red)
                    }
                }
            }
            Section("Active Conditions") {
                let conditions = vm.summary?.activeDiagnoses ?? []
                if conditions.isEmpty {
                    Text("No active conditions").foregroundColor(.secondary)
                } else {
                    ForEach(conditions, id: \.self) { Text($0) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Encounters
    private var encountersTab: some View {
        Group {
            if vm.encounters.isEmpty {
                ContentUnavailableView("No Encounters", systemImage: "building.2.fill",
                    description: Text("No encounters found."))
            } else {
                List(vm.encounters) { enc in
                    EncounterRowView(encounter: enc)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Labs
    private var labsTab: some View {
        Group {
            if vm.labs.isEmpty {
                ContentUnavailableView("No Lab Results", systemImage: "testtube.2",
                    description: Text("No lab results on file."))
            } else {
                List(vm.labs) { lab in
                    LabResultDetailRow(result: lab)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Medications
    private var medicationsTab: some View {
        Group {
            if vm.medications.isEmpty {
                ContentUnavailableView("No Medications", systemImage: "pill.fill",
                    description: Text("No medications on record."))
            } else {
                List(vm.medications) { med in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(med.displayName).font(.headline)
                            if let dosage = med.dosage { Text(dosage).font(.subheadline).foregroundColor(.secondary) }
                        }
                        Spacer()
                        StatusBadge(text: med.status?.capitalized ?? "Active",
                                    color: med.status?.uppercased() == "ACTIVE" ? "green" : "gray")
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Immunizations
    private var immunizationsTab: some View {
        Group {
            if vm.immunizations.isEmpty {
                ContentUnavailableView("No Immunizations", systemImage: "syringe.fill",
                    description: Text("No immunization records."))
            } else {
                List(vm.immunizations) { imm in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(imm.vaccineName ?? "Vaccine").font(.headline)
                            Spacer()
                            StatusBadge(text: imm.status?.capitalized ?? "—", color: "green")
                        }
                        if let provider = imm.provider {
                            Text("By \(provider)").font(.caption).foregroundColor(.secondary)
                        }
                        if let date = imm.dateAdministered {
                            Text(date).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

struct TabChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline).bold()
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(isSelected ? Color.accentColor : Color(.secondarySystemBackground))
                .foregroundColor(isSelected ? .white : .primary)
                .cornerRadius(20)
        }
    }
}

@MainActor
final class HealthRecordsViewModel: ObservableObject {
    @Published var summary: HealthSummaryDTO?
    @Published var encounters: [EncounterDTO] = []
    @Published var labs: [LabResultDTO] = []
    @Published var medications: [MedicationDTO] = []
    @Published var immunizations: [ImmunizationDTO] = []
    @Published var isLoading = false

    func loadAll() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                self.summary = try? await APIClient.shared.get(APIEndpoints.healthSummary)
            }
            group.addTask { @MainActor in
                self.encounters = (try? await APIClient.shared.get(APIEndpoints.encounters)) ?? []
            }
            group.addTask { @MainActor in
                self.labs = (try? await APIClient.shared.get(
                    APIEndpoints.labResults,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.medications = (try? await APIClient.shared.get(
                    APIEndpoints.medications,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.immunizations = (try? await APIClient.shared.get(APIEndpoints.immunizations)) ?? []
            }
        }
        isLoading = false
    }
}

// MARK: - Sharing & Privacy (matches Angular my-sharing.ts)

struct SharingPrivacyView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = SharingPrivacyViewModel()
    @State private var selectedTab = 0

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.load() }
        } else {
            content
                .task { await vm.load() }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                Text("Consents").tag(0)
                Text("Access Log").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            if vm.isLoading {
                ProgressView("Loading…").padding()
            } else if selectedTab == 0 {
                consentsList
            } else {
                accessLogList
            }
        }
        .navigationTitle("sharing_privacy".localized)
        .refreshable { await vm.load() }
    }

    private var consentsList: some View {
        Group {
            if vm.consents.isEmpty {
                ContentUnavailableView("No Consents", systemImage: "lock.shield",
                    description: Text("No active sharing consents."))
            } else {
                List {
                    ForEach(vm.consents) { consent in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(consent.toHospitalName ?? "Hospital").font(.headline)
                                    if let purpose = consent.purpose {
                                        Text(purpose).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                                Spacer()
                                StatusBadge(text: consent.status?.capitalized ?? "Active",
                                            color: consent.status?.uppercased() == "ACTIVE" ? "green" : "gray")
                            }
                            if let granted = consent.grantedAt {
                                Text("Granted: \(granted)").font(.caption2).foregroundColor(.secondary)
                            }
                            if let expires = consent.expiresAt {
                                Text("Expires: \(expires)").font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                Task {
                                    await vm.revokeConsent(
                                        fromHospitalId: consent.fromHospitalId ?? "",
                                        toHospitalId: consent.toHospitalId ?? ""
                                    )
                                }
                            } label: {
                                Label("Revoke", systemImage: "xmark.circle")
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    private var accessLogList: some View {
        Group {
            if vm.accessLog.isEmpty {
                ContentUnavailableView("No Access Records", systemImage: "eye.slash",
                    description: Text("No one has viewed your records."))
            } else {
                List(vm.accessLog) { log in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(log.accessedBy ?? "Unknown").font(.subheadline).bold()
                            Spacer()
                            if let role = log.accessedByRole {
                                Text(role).font(.caption).foregroundColor(.secondary)
                            }
                        }
                        if let resource = log.resourceAccessed {
                            Text(resource).font(.caption).foregroundColor(.secondary)
                        }
                        if let type = log.accessType {
                            Text(type.capitalized).font(.caption2).foregroundColor(.accentColor)
                        }
                        if let date = log.accessedAt {
                            Text(date).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.insetGrouped)
            }
        }
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
                    queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
                )
                self.consents = page?.content ?? []
                // Fallback: try direct array
                if self.consents.isEmpty {
                    self.consents = (try? await APIClient.shared.get(APIEndpoints.consents)) ?? []
                }
            }
            group.addTask { @MainActor in
                let page: PageDTO<AccessLogDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.accessLog,
                    queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
                )
                self.accessLog = page?.content ?? []
                if self.accessLog.isEmpty {
                    self.accessLog = (try? await APIClient.shared.get(APIEndpoints.accessLog)) ?? []
                }
            }
        }
        isLoading = false
    }

    func revokeConsent(fromHospitalId: String, toHospitalId: String) async {
        let _: String? = try? await APIClient.shared.delete(
            APIEndpoints.consents,
            queryItems: [
                URLQueryItem(name: "fromHospitalId", value: fromHospitalId),
                URLQueryItem(name: "toHospitalId", value: toHospitalId)
            ]
        )
        await load()
    }
}
