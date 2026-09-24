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
                    // NOT a Button wrapping the whole row: the refill button
                    // below is a control of its own, and an outer Button's
                    // gesture covers its entire label, so tapping "Request
                    // refill" would open the detail sheet instead — or set
                    // both sheet items, of which SwiftUI presents only one.
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(rx.displayName).font(.headline)
                                    Spacer()
                                    StatusBadge(text: rx.statusEnum.localizedLabel,
                                                color: rx.statusEnum.tone.badgeColor)
                                }
                                if let dosage = rx.dosage, let freq = rx.frequency {
                                    Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                                }
                                if let dr = rx.staffFullName, !dr.isEmpty {
                                    Text(String(format: "prescribed_by_with_value".localized, dr))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                // Where the order went, when it left the hospital's
                                // own dispensary (PrescriptionResponseDTO.pharmacyName).
                                if let pharmacy = rx.pharmacyName, !pharmacy.isEmpty {
                                    Text(String(format: "rx_pharmacy_with_value".localized, pharmacy))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                            Spacer(minLength: 4)
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary).font(.caption)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { selectedRx = rx }

                        // The backend gate is PrescriptionStatus.isRefillable(),
                        // not a refill counter — this DTO has never carried one.
                        if rx.statusEnum.isRefillable {
                            // The backend allows ONE open request per
                            // prescription (requestMedicationRefill). Until this
                            // change the button never rendered at all, so that
                            // refusal was unreachable; now the patient is told
                            // before tapping rather than after a 400. It is a
                            // courtesy, not the gate: the server re-checks and
                            // its message reaches the alert.
                            if vm.hasOpenRefill(forPrescription: rx.id) {
                                Text("refill_already_open".localized)
                                    .font(.caption2).foregroundColor(.secondary)
                            } else {
                                HStack {
                                    Spacer()
                                    Button {
                                        refillTarget = rx
                                    } label: {
                                        Label("request_refill".localized, systemImage: "arrow.clockwise.circle.fill")
                                            .font(.caption)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.mini)
                                }
                            }
                        }
                    }
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
                            StatusBadge(text: refill.statusEnum.localizedLabel,
                                        color: refill.statusEnum.tone.badgeColor)
                        }
                        if let pharmacy = refill.preferredPharmacy, !pharmacy.isEmpty {
                            Text(String(format: "rx_pharmacy_with_value".localized, pharmacy))
                                .font(.subheadline).foregroundColor(.secondary)
                        }
                        if let requested = refill.requestedAt {
                            Text(String(format: "refill_requested_with_value".localized, Self.formatDate(requested)))
                                .font(.caption).foregroundColor(.secondary)
                        }
                        if refill.statusEnum == .requested {
                            Text("refill_sent_for_review".localized)
                                .font(.caption).foregroundColor(.secondary)
                        }
                        if let updated = refill.updatedAt, updated != refill.requestedAt {
                            Text(String(format: "refill_updated_with_value".localized, Self.formatDate(updated)))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if let providerNotes = refill.providerNotes, !providerNotes.isEmpty {
                            Text(String(format: "refill_provider_with_value".localized, providerNotes))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if let notes = refill.notes, !notes.isEmpty {
                            Text(String(format: "refill_notes_with_value".localized, notes))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                    .swipeActions(edge: .trailing) {
                        // REQUESTED and PAUSED are the two states cancelMyRefill
                        // lets the patient withdraw. PENDING is not a status this
                        // backend has ever had, and PAUSED was never offered.
                        if refill.statusEnum.isCancellable {
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
                Section("prescription".localized) {
                    HStack { Text("medication".localized).foregroundColor(.secondary); Spacer(); Text(prescription.displayName) }
                    if let dosage = prescription.dosage {
                        HStack { Text("dosage".localized).foregroundColor(.secondary); Spacer(); Text(dosage) }
                    }
                    if let freq = prescription.frequency {
                        HStack { Text("frequency".localized).foregroundColor(.secondary); Spacer(); Text(freq) }
                    }
                    HStack {
                        Text("status".localized).foregroundColor(.secondary)
                        Spacer()
                        StatusBadge(text: prescription.statusEnum.localizedLabel,
                                    color: prescription.statusEnum.tone.badgeColor)
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
                Section("prescription".localized) {
                    detailRow("medication".localized, prescription.displayName)
                    HStack {
                        Text("status".localized).foregroundColor(.secondary)
                        Spacer()
                        StatusBadge(text: prescription.statusEnum.localizedLabel,
                                    color: prescription.statusEnum.tone.badgeColor)
                    }
                }

                Section("Dosage & Administration") {
                    if let dosage = prescription.dosage {
                        detailRow("dosage".localized, dosage)
                    }
                    if let freq = prescription.frequency {
                        detailRow("frequency".localized, freq)
                    }
                    if let duration = prescription.duration {
                        detailRow("duration".localized, duration)
                    }
                    if let route = prescription.route {
                        detailRow("route".localized, route)
                    }
                }

                Section("dates".localized) {
                    if let prescribed = prescription.createdAt {
                        detailRow("prescribed".localized, String(prescribed.prefix(10)))
                    }
                }

                if let dr = prescription.staffFullName, !dr.isEmpty {
                    Section("provider".localized) {
                        detailRow("prescribed_by".localized, dr)
                    }
                }

                if let pharmacy = prescription.pharmacyName, !pharmacy.isEmpty {
                    Section("pharmacy".localized) {
                        detailRow("pharmacy".localized, pharmacy)
                    }
                }

                if let instructions = prescription.instructions, !instructions.isEmpty {
                    Section("instructions".localized) {
                        Text(instructions).font(.body)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(prescription.displayName)
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
                // Newest first: `hasOpenRefill` can only see this page, and
                // an open REQUESTED/PAUSED row outside it would put the button
                // back on screen. The backend sorts on whatever Pageable says.
                let page: PageDTO<RefillDTO>? = try? await APIClient.shared.get(
                    APIEndpoints.refills,
                    queryItems: [
                        URLQueryItem(name: "size", value: "50"),
                        URLQueryItem(name: "sort", value: "createdAt,desc")
                    ]
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

    /// Whether a REQUESTED or PAUSED refill already exists for this
    /// prescription, which is what `requestMedicationRefill` refuses on.
    func hasOpenRefill(forPrescription prescriptionId: String?) -> Bool {
        guard let prescriptionId, !prescriptionId.isEmpty else { return false }
        return refills.contains { $0.prescriptionId == prescriptionId && $0.statusEnum.isOpen }
    }

    func cancelRefill(id: String) async {
        let _: RefillDTO? = try? await APIClient.shared.put(APIEndpoints.cancelRefill(id: id))
        await load()
    }
}
