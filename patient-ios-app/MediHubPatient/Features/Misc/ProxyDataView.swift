import SwiftUI

// MARK: - Proxy data viewer (matches Angular proxy-data-viewer and Android ProxyDataScreen)

/// The five things a grantor can let a proxy see, each behind its own
/// permission and its own /me/patient/proxy-access/{patientId}/… endpoint.
enum ProxyDataKind: String, CaseIterable, Identifiable {
    case records = "VIEW_RECORDS"
    case appointments = "VIEW_APPOINTMENTS"
    case medications = "VIEW_MEDICATIONS"
    case labResults = "VIEW_LAB_RESULTS"
    case billing = "VIEW_BILLING"

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .records: "proxy_data_records"
        case .appointments: "proxy_data_appointments"
        case .medications: "proxy_data_medications"
        case .labResults: "proxy_data_lab_results"
        case .billing: "proxy_data_billing"
        }
    }

    var icon: String {
        switch self {
        case .records: "heart.text.square.fill"
        case .appointments: "calendar"
        case .medications: "pill.fill"
        case .labResults: "testtube.2"
        case .billing: "creditcard.fill"
        }
    }

    /// The backend treats ALL as every permission, and older grants carry
    /// un-prefixed tokens (APPOINTMENTS, RECORDS, …) that the web maps back;
    /// an exact VIEW_* match showed "nothing viewable" for both.
    static func allowed(by permissions: [String]) -> [ProxyDataKind] {
        let tokens = Set(permissions.map { $0.trimmingCharacters(in: .whitespaces).uppercased() })
        if tokens.contains("ALL") { return allCases }
        return allCases.filter { kind in
            tokens.contains(kind.rawValue) || tokens.contains(kind.rawValue.replacingOccurrences(of: "VIEW_", with: ""))
        }
    }

    func path(patientId: String) -> String {
        switch self {
        case .records: APIEndpoints.proxyRecords(patientId: patientId)
        case .appointments: APIEndpoints.proxyAppointments(patientId: patientId)
        case .medications: APIEndpoints.proxyMedications(patientId: patientId)
        case .labResults: APIEndpoints.proxyLabResults(patientId: patientId)
        case .billing: APIEndpoints.proxyBilling(patientId: patientId)
        }
    }
}

struct ProxyDataView: View {
    let patientId: String
    let patientName: String
    let kind: ProxyDataKind
    @StateObject private var vm = ProxyDataViewModel()

    var body: some View {
        Group {
            // Spinner and error only when there is nothing on screen yet: a
            // pull-to-refresh keeps the list, and a failed refresh keeps the
            // data rather than swapping it for an error page.
            if vm.isLoading, !vm.hasData(for: kind) {
                ProgressView("loading".localized)
            } else if let error = vm.errorMessage, !vm.hasData(for: kind) {
                // A 403 (permission withdrawn since the list loaded) or an
                // outage must read as an error, never as "nothing here".
                ContentUnavailableView {
                    Label("proxy_data_error".localized, systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error)
                } actions: {
                    Button("retry".localized) { Task { await vm.load(patientId: patientId, kind: kind) } }
                }
            } else {
                content
            }
        }
        .navigationTitle(kind.titleKey.localized)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top) {
            Text(String(format: "proxy_data_viewing_for".localized, patientName))
                .font(.caption)
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(.thinMaterial)
        }
        .task { await vm.load(patientId: patientId, kind: kind) }
        .refreshable { await vm.load(patientId: patientId, kind: kind) }
    }

    @ViewBuilder
    private var content: some View {
        switch kind {
        case .appointments:
            if vm.appointments.isEmpty {
                ContentUnavailableView("proxy_data_none".localized, systemImage: kind.icon)
            } else {
                List(vm.appointments) { appt in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(appt.staffName ?? "—").font(.headline)
                        Text([appt.appointmentDate, appt.startTime].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundColor(.secondary)
                        if let hospital = appt.hospitalName {
                            Text(hospital).font(.caption).foregroundColor(.secondary)
                        }
                        if let status = appt.status {
                            StatusBadge(text: status.capitalized, color: status.uppercased() == "CANCELLED" ? "red" : "blue")
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        case .medications:
            if vm.medications.isEmpty {
                ContentUnavailableView("proxy_data_none".localized, systemImage: kind.icon)
            } else {
                List(vm.medications) { med in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(med.displayName).font(.headline)
                        Text([med.dosage, med.frequency].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundColor(.secondary)
                        if let by = med.prescribedBy {
                            Text(by).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        case .labResults:
            if vm.labResults.isEmpty {
                ContentUnavailableView("proxy_data_none".localized, systemImage: kind.icon)
            } else {
                List(vm.labResults) { result in
                    LabResultSummaryRow(result: result)
                }
                .listStyle(.insetGrouped)
            }
        case .billing:
            if vm.invoices.isEmpty {
                ContentUnavailableView("proxy_data_none".localized, systemImage: kind.icon)
            } else {
                List(vm.invoices) { invoice in
                    InvoiceRowView(invoice: invoice)
                }
                .listStyle(.insetGrouped)
            }
        case .records:
            if let summary = vm.records {
                recordsSummary(summary)
            } else {
                ContentUnavailableView("proxy_data_none".localized, systemImage: kind.icon)
            }
        }
    }

    private func recordsSummary(_ summary: HealthSummaryDTO) -> some View {
        List {
            if let profile = summary.profile {
                Section("proxy_data_patient".localized) {
                    LabeledContent("name".localized, value: profile.fullName)
                    if let dob = profile.dateOfBirth { LabeledContent("date_of_birth".localized, value: dob) }
                    if let blood = profile.bloodType { LabeledContent("blood_type".localized, value: blood) }
                }
            }
            Section("allergies".localized) {
                if let allergies = summary.allergies, !allergies.isEmpty {
                    ForEach(allergies, id: \.self) { Text($0) }
                } else {
                    Text("no_known_allergies".localized).foregroundColor(.secondary)
                }
            }
            Section("active_conditions".localized) {
                let conditions = (summary.activeDiagnoses ?? []) + (summary.chronicConditions ?? [])
                if conditions.isEmpty {
                    Text("none".localized).foregroundColor(.secondary)
                } else {
                    ForEach(conditions, id: \.self) { Text($0) }
                }
            }
            if let meds = summary.currentMedications, !meds.isEmpty {
                Section("medications_title".localized) {
                    ForEach(meds) { med in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(med.displayName).font(.subheadline).bold()
                            Text([med.dosage, med.frequency].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            }
            if let labs = summary.recentLabResults, !labs.isEmpty {
                Section("lab_results_title".localized) {
                    ForEach(labs) { LabResultSummaryRow(result: $0) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

@MainActor
final class ProxyDataViewModel: ObservableObject {
    @Published var appointments: [AppointmentDTO] = []
    @Published var medications: [MedicationDTO] = []
    @Published var labResults: [LabResultDTO] = []
    @Published var invoices: [InvoiceDTO] = []
    @Published var records: HealthSummaryDTO?
    @Published var isLoading = false
    @Published var errorMessage: String?

    func hasData(for kind: ProxyDataKind) -> Bool {
        switch kind {
        case .appointments: !appointments.isEmpty
        case .medications: !medications.isEmpty
        case .labResults: !labResults.isEmpty
        case .billing: !invoices.isEmpty
        case .records: records != nil
        }
    }

    func load(patientId: String, kind: ProxyDataKind) async {
        isLoading = true
        errorMessage = nil
        do {
            switch kind {
            case .appointments:
                appointments = try await APIClient.shared.get(kind.path(patientId: patientId))
            case .medications:
                medications = try await APIClient.shared.get(
                    kind.path(patientId: patientId),
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )
            case .labResults:
                labResults = try await APIClient.shared.get(
                    kind.path(patientId: patientId),
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )
            case .billing:
                let page: PageDTO<InvoiceDTO> = try await APIClient.shared.get(
                    kind.path(patientId: patientId),
                    queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
                )
                invoices = page.content
            case .records:
                records = try await APIClient.shared.get(kind.path(patientId: patientId))
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}
