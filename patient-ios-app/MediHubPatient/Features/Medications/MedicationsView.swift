import SwiftUI

struct MedicationsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = MedicationsViewModel()
    @State private var selectedTab = 0
    @State private var refillTarget: PrescriptionDTO?
    @State private var showError = false
    @State private var selectedMed: MedicationDTO?
    @State private var selectedRx: PrescriptionDTO?

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
                Text("medications_title".localized).tag(0)
                Text("prescriptions".localized).tag(1)
                Text("refills".localized).tag(2)
            }
            .pickerStyle(.segmented)
            .padding()

            if vm.isLoading {
                ProgressView().padding()
            } else {
                switch selectedTab {
                case 0: medicationsList
                case 1: prescriptionsList
                default: refillsList
                }
            }
        }
        .navigationTitle("medications_title".localized)
        .refreshable { await vm.load() }
        .onChange(of: vm.errorMessage) { _, newVal in showError = (newVal != nil) }
        .alert("Error", isPresented: $showError) {
            Button("OK") { vm.errorMessage = nil }
        } message: {
            Text(vm.errorMessage ?? "")
        }
        .sheet(item: $refillTarget) { rx in
            RefillRequestSheet(prescription: rx, vm: vm, isPresented: $refillTarget)
        }
        .sheet(item: $selectedMed) { med in
            MedicationDetailSheet(medication: med)
        }
        .sheet(item: $selectedRx) { rx in
            PrescriptionDetailSheet(prescription: rx)
        }
    }

    // MARK: - Medications Tab

    private var medicationsList: some View {
        Group {
            if vm.medications.isEmpty {
                ContentUnavailableView("No Medications",
                                       systemImage: "pill.fill",
                                       description: Text("No active medications on record."))
            } else {
                List(vm.medications) { med in
                    Button { selectedMed = med } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(med.displayName).font(.headline)
                                    Spacer()
                                    StatusBadge(text: med.status?.capitalized ?? "Active",
                                                color: med.status?.uppercased() == "ACTIVE" ? "green" : "gray")
                                }
                                if let dosage = med.dosage, let freq = med.frequency {
                                    Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                                }
                                if let dr = med.prescribedBy {
                                    Text("Prescribed by \(dr)").font(.caption).foregroundColor(.secondary)
                                }
                                if let start = med.startDate {
                                    Text("Since \(start)").font(.caption2).foregroundColor(.secondary)
                                }
                            }
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary).font(.caption)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: - Prescriptions Tab

    private var prescriptionsList: some View {
        Group {
            if vm.prescriptions.isEmpty {
                ContentUnavailableView("No Prescriptions",
                                       systemImage: "doc.text.fill",
                                       description: Text("No prescriptions on record."))
            } else {
                List(vm.prescriptions) { rx in
                    Button { selectedRx = rx } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(rx.medicationName ?? "Prescription").font(.headline)
                                    Spacer()
                                    StatusBadge(text: rx.status?.capitalized ?? "Active",
                                                color: "blue")
                                }
                                if let dosage = rx.dosage, let freq = rx.frequency {
                                    Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                                }
                                if let dr = rx.prescribedBy {
                                    Text("By \(dr)").font(.caption).foregroundColor(.secondary)
                                }

                                HStack {
                                    if let refills = rx.refillsRemaining {
                                        Text("\(refills) refill(s) remaining")
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    if (rx.refillsRemaining ?? 0) > 0 {
                                        Button {
                                            refillTarget = rx
                                        } label: {
                                            Label("Request Refill", systemImage: "arrow.clockwise.circle.fill")
                                                .font(.caption)
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .controlSize(.mini)
                                    }
                                }
                            }
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary).font(.caption)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: - Refills Tab

    private var refillsList: some View {
        Group {
            if vm.refills.isEmpty {
                ContentUnavailableView("No Refills",
                                       systemImage: "arrow.clockwise",
                                       description: Text("No refill requests on record."))
            } else {
                List(vm.refills) { refill in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(refill.medicationName ?? "Refill").font(.headline)
                            Spacer()
                            StatusBadge(text: refill.status?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Pending",
                                        color: refillStatusColor(refill.status))
                        }
                        if let pharmacy = refill.preferredPharmacy, !pharmacy.isEmpty {
                            Text("Pharmacy: \(pharmacy)").font(.subheadline).foregroundColor(.secondary)
                        }
                        if let requested = refill.requestedAt {
                            Text("Requested: \(Self.formatDate(requested))").font(.caption).foregroundColor(.secondary)
                        }
                        if let updated = refill.updatedAt, updated != refill.requestedAt {
                            Text("Updated: \(Self.formatDate(updated))").font(.caption2).foregroundColor(.secondary)
                        }
                        if let providerNotes = refill.providerNotes, !providerNotes.isEmpty {
                            Text("Provider: \(providerNotes)").font(.caption2).foregroundColor(.secondary)
                        }
                        if let notes = refill.notes, !notes.isEmpty {
                            Text("Notes: \(notes)").font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                    .swipeActions(edge: .trailing) {
                        if refill.status?.uppercased() == "PENDING" || refill.status?.uppercased() == "REQUESTED" {
                            Button(role: .destructive) {
                                Task { await vm.cancelRefill(id: refill.id ?? "") }
                            } label: {
                                Label("Cancel", systemImage: "xmark.circle")
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    private func refillStatusColor(_ status: String?) -> String {
        switch status?.uppercased() {
        case "COMPLETED", "FILLED", "DISPENSED": "green"
        case "APPROVED": "blue"
        case "PENDING", "REQUESTED": "yellow"
        case "CANCELLED", "DENIED": "red"
        default: "gray"
        }
    }

    /// Format ISO-8601 datetime string to a short display like "Apr 11, 2026"
    private static func formatDate(_ iso: String) -> String {
        let isoFormatter = ISO8601DateFormatter()
        isoFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = isoFormatter.date(from: iso) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        isoFormatter.formatOptions = [.withInternetDateTime]
        if let date = isoFormatter.date(from: iso) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        // Fallback: show first 10 chars (date portion)
        return String(iso.prefix(10))
    }
}

// MARK: - Refill Request Sheet

struct RefillRequestSheet: View {
    let prescription: PrescriptionDTO
    @ObservedObject var vm: MedicationsViewModel
    @Binding var isPresented: PrescriptionDTO?
    @State private var pharmacy = ""
    @State private var notes = ""
    @State private var isSubmitting = false
    @State private var errorMsg: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Prescription") {
                    HStack { Text("Medication").foregroundColor(.secondary); Spacer(); Text(prescription.medicationName ?? "—") }
                    if let dosage = prescription.dosage {
                        HStack { Text("Dosage").foregroundColor(.secondary); Spacer(); Text(dosage) }
                    }
                    if let freq = prescription.frequency {
                        HStack { Text("Frequency").foregroundColor(.secondary); Spacer(); Text(freq) }
                    }
                    if let refills = prescription.refillsRemaining {
                        HStack { Text("Refills Remaining").foregroundColor(.secondary); Spacer(); Text("\(refills)") }
                    }
                }

                Section("Refill Details") {
                    TextField("Preferred Pharmacy (optional)", text: $pharmacy)
                    TextField("Notes (optional)", text: $notes, axis: .vertical)
                        .lineLimit(3)
                }

                if let err = errorMsg {
                    Section { Text(err).foregroundColor(.red).font(.caption) }
                }
            }
            .navigationTitle("Request Refill")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Submit") { Task { await submit() } }
                        .disabled(isSubmitting)
                        .bold()
                }
            }
            .interactiveDismissDisabled(isSubmitting)
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMsg = nil
        let result = await vm.requestRefill(
            prescriptionId: prescription.id ?? "",
            pharmacy: pharmacy.isEmpty ? nil : pharmacy,
            notes: notes.isEmpty ? nil : notes
        )
        if let err = result {
            errorMsg = err
        } else {
            isPresented = nil
        }
        isSubmitting = false
    }
}

// MARK: - Medication Detail Sheet

struct MedicationDetailSheet: View {
    let medication: MedicationDTO
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Medication") {
                    detailRow("Name", medication.displayName)
                    if let generic = medication.genericName, !generic.isEmpty {
                        detailRow("Generic Name", generic)
                    }
                    if let status = medication.status {
                        HStack {
                            Text("Status").foregroundColor(.secondary)
                            Spacer()
                            StatusBadge(text: status.capitalized,
                                        color: status.uppercased() == "ACTIVE" ? "green" : "gray")
                        }
                    }
                }

                Section("Dosage & Administration") {
                    if let dosage = medication.dosage {
                        detailRow("Dosage", dosage)
                    }
                    if let freq = medication.frequency {
                        detailRow("Frequency", freq)
                    }
                    if let route = medication.route {
                        detailRow("Route", route)
                    }
                }

                Section("Dates") {
                    if let start = medication.startDate {
                        detailRow("Start Date", start)
                    }
                    if let end = medication.endDate {
                        detailRow("End Date", end)
                    }
                }

                if let dr = medication.prescribedBy {
                    Section("Provider") {
                        detailRow("Prescribed By", dr)
                    }
                }

                if let refills = medication.refillsRemaining {
                    Section("Refills") {
                        detailRow("Remaining", "\(refills)")
                    }
                }

                if let instructions = medication.instructions, !instructions.isEmpty {
                    Section("Instructions") {
                        Text(instructions).font(.body)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(medication.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value)
        }
    }
}

// MARK: - Prescription Detail Sheet

struct PrescriptionDetailSheet: View {
    let prescription: PrescriptionDTO
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Prescription") {
                    detailRow("Medication", prescription.medicationName ?? "—")
                    if let status = prescription.status {
                        HStack {
                            Text("Status").foregroundColor(.secondary)
                            Spacer()
                            StatusBadge(text: status.capitalized, color: "blue")
                        }
                    }
                }

                Section("Dosage & Administration") {
                    if let dosage = prescription.dosage {
                        detailRow("Dosage", dosage)
                    }
                    if let freq = prescription.frequency {
                        detailRow("Frequency", freq)
                    }
                    if let qty = prescription.quantity {
                        detailRow("Quantity", "\(qty)")
                    }
                }

                Section("Refills") {
                    if let remaining = prescription.refillsRemaining {
                        detailRow("Remaining", "\(remaining)")
                    }
                    if let total = prescription.refills {
                        detailRow("Total Authorized", "\(total)")
                    }
                }

                Section("Dates") {
                    if let prescribed = prescription.prescribedDate {
                        detailRow("Prescribed", prescribed)
                    }
                    if let expiry = prescription.expiryDate {
                        detailRow("Expires", expiry)
                    }
                }

                if let dr = prescription.prescribedBy {
                    Section("Provider") {
                        detailRow("Prescribed By", dr)
                    }
                }

                if prescription.diagnosisCode != nil || prescription.diagnosisDescription != nil {
                    Section("Diagnosis") {
                        if let code = prescription.diagnosisCode {
                            detailRow("Code", code)
                        }
                        if let desc = prescription.diagnosisDescription {
                            detailRow("Description", desc)
                        }
                    }
                }

                if let instructions = prescription.instructions, !instructions.isEmpty {
                    Section("Instructions") {
                        Text(instructions).font(.body)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(prescription.medicationName ?? "Prescription")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value)
        }
    }
}

// MARK: - View Model

@MainActor
final class MedicationsViewModel: ObservableObject {
    @Published var medications: [MedicationDTO] = []
    @Published var prescriptions: [PrescriptionDTO] = []
    @Published var refills: [RefillDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                self.medications = await (try? APIClient.shared.get(
                    APIEndpoints.medications,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.prescriptions = await (try? APIClient.shared.get(APIEndpoints.prescriptions)) ?? []
            }
            group.addTask { @MainActor in
                let page: PageDTO<RefillDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.refills,
                    queryItems: [URLQueryItem(name: "size", value: "50")]
                )
                self.refills = page?.content ?? []
            }
        }
        isLoading = false
    }

    func requestRefill(prescriptionId: String, pharmacy: String?, notes: String?) async -> String? {
        let req = RefillRequest(prescriptionId: prescriptionId, preferredPharmacy: pharmacy, notes: notes)
        do {
            let _: RefillDTO = try await APIClient.shared.post(APIEndpoints.refills, body: req)
            await load()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func cancelRefill(id: String) async {
        let _: RefillDTO? = try? await APIClient.shared.put(APIEndpoints.cancelRefill(id: id))
        await load()
    }
}
