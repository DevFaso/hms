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
            if vm.isLoading, vm.notifications.isEmpty { ProgressView("loading".localized) }
            else if let error = vm.errorMessage, vm.notifications.isEmpty {
                ContentUnavailableView {
                    Label("notifications_load_failed".localized, systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("retry".localized) { Task { await vm.load() } }
                }
            } else if vm.notifications.isEmpty {
                ContentUnavailableView("no_notifications".localized, systemImage: "bell.slash.fill",
                                       description: Text("no_notifications_desc".localized))
            } else {
                List(vm.notifications) { notif in
                    Button {
                        Task { await vm.markRead(notif) }
                    } label: {
                        NotificationRow(notification: notif)
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("notifications".localized)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("mark_all_read".localized) { Task { await vm.markAllRead() } }
                    .disabled(vm.unreadCount == 0)
            }
        }
        .refreshable { await vm.load() }
        .alert("notification_action_failed".localized, isPresented: Binding(
            get: { vm.actionError != nil },
            set: { if !$0 { vm.actionError = nil } }
        )) {
            Button("ok".localized, role: .cancel) {}
        } message: {
            Text(vm.actionError ?? "")
        }
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
    @Published var errorMessage: String?
    /// A failed mark-read is a one-shot alert; `errorMessage` is the list's own state.
    @Published var actionError: String?

    var unreadCount: Int { notifications.filter { !$0.isRead }.count }

    func load() async {
        isLoading = true
        errorMessage = nil
        do {
            let page: PageDTO<NotificationDTO> = try await APIClient.shared.get(
                APIEndpoints.notifications,
                queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
            )
            notifications = page.content
        } catch {
            // A failed load is an error, not an inbox with nothing in it.
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    /// Marks one notification read. The endpoints have been declared in
    /// APIEndpoints since the scaffold; nothing called them, so an iOS
    /// notification could never be marked read.
    func markRead(_ notification: NotificationDTO) async {
        guard let id = notification.id, !notification.isRead else { return }
        do {
            let _: NotificationDTO? = try await APIClient.shared.put(APIEndpoints.markNotificationRead(id: id))
            await load()
        } catch {
            actionError = error.localizedDescription
        }
    }

    func markAllRead() async {
        do {
            let _: [String: Int]? = try await APIClient.shared.put(APIEndpoints.markAllNotificationsRead)
            await load()
        } catch {
            actionError = error.localizedDescription
        }
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
            patientIdentityHeader

            // Tab picker
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    TabChip(title: "overview".localized, isSelected: selectedTab == 0) { selectedTab = 0 }
                    TabChip(title: "vitals".localized, isSelected: selectedTab == 1) { selectedTab = 1 }
                    TabChip(title: "lab_results".localized, isSelected: selectedTab == 2) { selectedTab = 2 }
                    TabChip(title: "medications".localized, isSelected: selectedTab == 3) { selectedTab = 3 }
                    TabChip(title: "immunizations".localized, isSelected: selectedTab == 4) { selectedTab = 4 }
                    TabChip(title: "treatment_plans".localized, isSelected: selectedTab == 5) { selectedTab = 5 }
                    TabChip(title: "referrals".localized, isSelected: selectedTab == 6) { selectedTab = 6 }
                    TabChip(title: "encounters".localized, isSelected: selectedTab == 7) { selectedTab = 7 }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 8)

            if vm.isLoading {
                ProgressView("loading".localized).padding()
            } else {
                switch selectedTab {
                case 0: overviewTab
                case 1: vitalsTab
                case 2: labsTab
                case 3: medicationsTab
                case 4: immunizationsTab
                case 5: treatmentPlansTab
                case 6: referralsTab
                case 7: encountersTab
                default: overviewTab
                }
            }
        }
        .navigationTitle("health_records".localized)
        .refreshable { await vm.loadAll() }
    }

    private var patientIdentityHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            let profile = vm.summary?.profile
            Text(profile?.fullName.isEmpty == false ? profile?.fullName ?? "my_chart".localized : "my_chart".localized)
                .font(.title3.bold())
                .foregroundColor(.accentColor)
            if let mrn = profile?.mrn, !mrn.isEmpty {
                Text(String(format: "mrn_format".localized, mrn)).font(.subheadline)
            }
            let details = [profile?.dateOfBirth.map { String(format: "dob_format".localized, String($0.prefix(10))) }, profile?.gender, profile?.bloodType]
                .compactMap { $0 }
                .filter { !$0.isEmpty }
            if !details.isEmpty {
                Text(details.joined(separator: "  |  "))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            let primarySource = profile?.hospitalName
                ?? profile?.primaryHospitalName
                ?? profile?.hospitalId.map { String(format: "hospital_id_format".localized, $0) }
                ?? profile?.primaryHospitalId.map { String(format: "hospital_id_format".localized, $0) }
            if let hospital = primarySource, !hospital.isEmpty {
                SourceLine(parts: [String(format: "primary_hospital_format".localized, hospital)])
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color.accentColor.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal)
        .padding(.top, 12)
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
            Section("Chronic Conditions") {
                let conditions = vm.summary?.chronicConditions ?? []
                if conditions.isEmpty {
                    Text("No chronic conditions").foregroundColor(.secondary)
                } else {
                    ForEach(conditions, id: \.self) { Text($0) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Vitals

    private var vitalsTab: some View {
        Group {
            let vitals = vm.summary?.allVitals ?? []
            if vitals.isEmpty {
                ContentUnavailableView("no_vitals".localized, systemImage: "waveform.path.ecg",
                                       description: Text("no_vitals_desc".localized))
            } else {
                List(vitals) { vital in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(vital.recordedDateDisplay).font(.headline)
                        Text(vital.allReadings.map { "\($0.label): \($0.value)" }.joined(separator: "  |  "))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        SourceLine(parts: [vital.hospitalName ?? vital.hospitalId.map { "Hospital ID \($0)" }, vital.recordedByName.map { "Recorded by \($0)" }, vital.sourceDisplay])
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Encounters

    private var encountersTab: some View {
        Group {
            if vm.encounters.isEmpty {
                ContentUnavailableView("No Encounters", systemImage: "building.2.fill",
                                       description: Text("No encounters found."))
            } else {
                List(vm.encounters) { enc in
                    VStack(alignment: .leading, spacing: 4) {
                        EncounterRowView(encounter: enc)
                        SourceLine(parts: [enc.hospitalName, enc.providerName, enc.department])
                    }
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
                    VStack(alignment: .leading, spacing: 4) {
                        LabResultSummaryRow(result: lab)
                        SourceLine(parts: [lab.hospitalName, lab.orderedBy.map { String(format: "ordered_by_with_value".localized, $0) }])
                    }
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
                            SourceLine(parts: [med.prescribedBy.map { "Prescribed by \($0)" }, med.frequency])
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

    // MARK: Treatment Plans

    private var treatmentPlansTab: some View {
        Group {
            if vm.treatmentPlans.isEmpty {
                ContentUnavailableView("no_treatment_plans".localized, systemImage: "list.clipboard",
                                       description: Text("no_treatment_plans".localized))
            } else {
                List(vm.treatmentPlans) { plan in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .top) {
                            Text(plan.title ?? "Treatment plan").font(.headline)
                            Spacer()
                            StatusBadge(text: plan.status?.capitalized ?? "—", color: "blue")
                        }
                        if let goals = plan.goals, !goals.isEmpty {
                            Text(goals).font(.subheadline).foregroundColor(.secondary)
                        }
                        if let doctor = plan.doctorName, !doctor.isEmpty {
                            SourceLine(parts: ["Created by \(doctor)"])
                        }
                        let dates = [plan.startDate, plan.endDate].compactMap { $0?.prefix(10) }
                        if !dates.isEmpty {
                            Text(dates.joined(separator: " - ")).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Referrals

    private var referralsTab: some View {
        Group {
            if vm.referrals.isEmpty {
                ContentUnavailableView("no_referrals".localized, systemImage: "arrowshape.turn.up.right.fill",
                                       description: Text("no_referrals".localized))
            } else {
                List(vm.referrals) { referral in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .top) {
                            Text(referral.toSpecialty ?? "referrals".localized).font(.headline)
                            Spacer()
                            StatusBadge(text: referral.status?.capitalized ?? "—", color: "blue")
                        }
                        if let doctor = referral.toDoctorName, !doctor.isEmpty {
                            Text(doctor).font(.subheadline).foregroundColor(.secondary)
                        }
                        if let reason = referral.reason, !reason.isEmpty {
                            Text(reason).font(.caption).foregroundColor(.secondary)
                        }
                        SourceLine(parts: [referral.toHospitalName, referral.fromDoctorName.map { "From \($0)" }, referral.urgency])
                        if let date = referral.referralDate {
                            Text(String(date.prefix(10))).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

private struct SourceLine: View {
    let parts: [String?]

    var body: some View {
        // The closure needs an explicit signature: it is multi-statement and
        // its only other `return` is a bare `nil`, so the compiler has nothing
        // to infer ElementOfResult from and the build fails outright.
        let text = parts.compactMap { (part: String?) -> String? in
            guard let value = part?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty, value != "—" else {
                return nil
            }
            return value
        }
        .reduce(into: [String]()) { values, value in
            if !values.contains(value) { values.append(value) }
        }
        .joined(separator: "  |  ")

        if !text.isEmpty {
            Text("Source: \(text)")
                .font(.caption)
                .fontWeight(.medium)
                .foregroundColor(Color("BrandBlue"))
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
    @Published var treatmentPlans: [TreatmentPlanDTO] = []
    @Published var referrals: [ReferralDTO] = []
    @Published var isLoading = false

    func loadAll() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                self.summary = try? await APIClient.shared.get(APIEndpoints.healthSummary)
            }
            group.addTask { @MainActor in
                self.encounters = await (try? APIClient.shared.get(APIEndpoints.encounters)) ?? []
            }
            group.addTask { @MainActor in
                self.labs = await (try? APIClient.shared.get(
                    APIEndpoints.labResults,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.medications = await (try? APIClient.shared.get(
                    APIEndpoints.medications,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.immunizations = await (try? APIClient.shared.get(APIEndpoints.immunizations)) ?? []
            }
            group.addTask { @MainActor in
                let page: PageDTO<TreatmentPlanDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.treatmentPlans,
                    queryItems: [URLQueryItem(name: "page", value: "0"),
                                 URLQueryItem(name: "size", value: "50")]
                )
                if let content = page?.content {
                    self.treatmentPlans = content
                } else {
                    self.treatmentPlans = await (try? APIClient.shared.get(APIEndpoints.treatmentPlans)) ?? []
                }
            }
            group.addTask { @MainActor in
                if let list: [ReferralDTO] = try? await APIClient.shared.get(APIEndpoints.referrals) {
                    self.referrals = list
                } else {
                    let page: PageDTO<ReferralDTO>? = try? await APIClient.shared.get(
                        APIEndpoints.referrals,
                        queryItems: [URLQueryItem(name: "page", value: "0"),
                                     URLQueryItem(name: "size", value: "50")]
                    )
                    self.referrals = page?.content ?? []
                }
            }
        }
        isLoading = false
    }
}
