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
        .alert("error".localized, isPresented: $showError) {
            Button("ok".localized) { vm.errorMessage = nil }
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
                ContentUnavailableView("no_active_medications".localized,
                                       systemImage: "pill.fill",
                                       description: Text("no_active_medications_desc".localized))
            } else {
                List(vm.medications) { med in
                    Button { selectedMed = med } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(med.displayName).font(.headline)
                                    Spacer()
                                    StatusBadge(text: med.statusEnum.localizedLabel,
                                                color: med.statusEnum.tone.badgeColor)
                                }
                                if let dosage = med.dosage, let freq = med.frequency {
                                    Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                                }
                                if let dr = med.prescribedBy {
                                    Text(String(format: "prescribed_by_with_value".localized, dr))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                if let start = med.startDate {
                                    Text(String(format: "since_with_value".localized, String(start.prefix(10))))
                                        .font(.caption2).foregroundColor(.secondary)
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
                ContentUnavailableView("no_prescriptions".localized,
                                       systemImage: "doc.text.fill",
                                       description: Text("no_prescriptions_desc".localized))
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
                        // The outer Button used to give VoiceOver and Switch
                        // Control the trait and the activation for free; a bare
                        // onTapGesture gives neither, and this is the only route
                        // to the prescription detail.
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { selectedRx = rx }

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
                            if let open = vm.openRefillStatus(forPrescription: rx.id) {
                                Text(MedicationsViewModel.openRefillMessageKey(open).localized)
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
                ContentUnavailableView("no_refills".localized,
                                       systemImage: "arrow.clockwise",
                                       description: Text("no_refills_desc".localized))
            } else {
                List(vm.refills) { refill in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(refill.medicationName ?? "refill".localized).font(.headline)
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
                                Label("cancel".localized, systemImage: "xmark.circle")
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

                Section("refill_details".localized) {
                    TextField("preferred_pharmacy".localized, text: $pharmacy)
                    TextField("notes_optional".localized, text: $notes, axis: .vertical)
                        .lineLimit(3)
                }

                if let err = errorMsg {
                    Section { Text(err).foregroundColor(.red).font(.caption) }
                }
            }
            .navigationTitle("request_refill".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { isPresented = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("submit".localized) { Task { await submit() } }
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
                    HStack {
                        Text("status".localized).foregroundColor(.secondary)
                        Spacer()
                        StatusBadge(text: medication.statusEnum.localizedLabel,
                                    color: medication.statusEnum.tone.badgeColor)
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
                    Button("done".localized) { dismiss() }
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

                if prescription.dosage != nil || prescription.frequency != nil
                    || prescription.duration != nil || prescription.route != nil {
                    Section("dosage_and_administration".localized) {
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
                        Text(pharmacy)
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
                    Button("done".localized) { dismiss() }
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
        // Keeping the previous lists on failure (below) removed the only
        // signal a refresh had failed — the screen used to empty. Report the
        // first failure instead, so an expired session is not a silent no-op.
        let failure: String? = await withTaskGroup(of: String?.self) { group in
            // Each list is replaced only when its own fetch SUCCEEDS. A
            // refresh that throws — no connectivity, an expired session, or
            // the reload on the refill-failure path below — used to swap the
            // patient's medications for "No active medications on record."
            group.addTask { @MainActor in
                // The endpoint defaults to 20 and the tab lists everything
                // it is given; the prescriptions tab also joins on these rows
                // for `refillRequestOpen`, which only helps for the
                // prescriptions the window covers.
                do {
                    self.medications = try await APIClient.shared.get(
                        APIEndpoints.medications,
                        queryItems: [URLQueryItem(name: "limit", value: "100")]
                    )
                    return nil
                } catch { return error.localizedDescription }
            }
            group.addTask { @MainActor in
                do {
                    self.prescriptions = try await APIClient.shared.get(APIEndpoints.prescriptions)
                    return nil
                } catch { return error.localizedDescription }
            }
            group.addTask { @MainActor in
                // Newest first: the refills-page fallback below can only see
                // this page, and an open REQUESTED/PAUSED row outside it would
                // put the button back on screen.
                do {
                    let page: PageDTO<RefillDTO> = try await APIClient.shared.get(
                        APIEndpoints.refills,
                        queryItems: [
                            URLQueryItem(name: "size", value: "50"),
                            URLQueryItem(name: "sort", value: "createdAt,desc")
                        ]
                    )
                    self.refills = page.content
                    return nil
                } catch { return error.localizedDescription }
            }
            var first: String?
            for await result in group where first == nil {
                first = result
            }
            return first
        }
        // `APIError.errorDescription` is English-only ("Session expired…",
        // "Server error (500)"), and this alert was dead before this PR made
        // it live — a French patient would have met it as a fully English
        // modal on the one screen the PR exists to de-anglicise. What matters
        // here is that the lists on screen are stale, which is sayable.
        errorMessage = failure == nil ? nil : "refresh_failed".localized
        isLoading = false
    }

    func requestRefill(prescriptionId: String, pharmacy: String?, notes: String?) async -> String? {
        let req = RefillRequest(prescriptionId: prescriptionId, preferredPharmacy: pharmacy, notes: notes)
        do {
            let _: RefillDTO = try await APIClient.shared.post(APIEndpoints.refills, body: req)
            // The request went through, but if the reload fails the lists on
            // screen no longer show it — the new row is missing from the
            // Refills tab and the prescription still offers "Request refill".
            // Letting `load()`'s own error stand is what stops the patient
            // submitting a second time into a 400.
            await load()
            return nil
        } catch {
            // APIClient lifts the server's `message` into the error, but that
            // sentence is English-only. Refresh and name the two refusals this
            // endpoint actually raises in the patient's own language, keeping
            // the server's words for anything else.
            let serverMessage = error.localizedDescription
            // The refusal itself is reported inline by the sheet below; any
            // error `load()` raises here is a different fact — the lists are
            // stale — and is left to stand.
            await load()
            // requestMedicationRefill checks isRefillable() BEFORE the
            // one-open-request guard, so a prescription discontinued since the
            // screen loaded is refused for that reason even when an open refill
            // also exists — ask in the same order.
            if let refreshed = prescriptions.first(where: { $0.id == prescriptionId }),
               !refreshed.statusEnum.isRefillable {
                return "refill_not_refillable".localized
            }
            // Here — unlike the button, where a stale "open" row would wrongly
            // BLOCK the patient — the server has already refused, so an open
            // row from EITHER source is a better answer than an English
            // sentence. It also covers what `refillRequestOpen` cannot see:
            // `latestRefillsFor` grades only the NEWEST request, so an older
            // PAUSED one that the server's `findFirst…StatusIn` still counts
            // reads as false.
            let open = openRefillStatus(forPrescription: prescriptionId)
                ?? refills.first { $0.prescriptionId == prescriptionId && $0.statusEnum.isOpen }?
                    .statusEnum
            if let open {
                return Self.openRefillMessageKey(open).localized
            }
            return serverMessage
        }
    }

    /// The state of the REQUESTED or PAUSED refill already on this
    /// prescription, which is what `requestMedicationRefill` refuses on, or
    /// nil when there is none.
    ///
    /// `PatientMedicationResponseDTO` is built from prescriptions and its `id`
    /// IS the prescription id, and its `refillRequestOpen` is computed over
    /// EVERY refill row rather than a page — so prefer it. The scan over the
    /// loaded refills page is the fallback for a prescription outside the
    /// medications window; either way the server re-checks.
    func openRefillStatus(forPrescription prescriptionId: String?) -> RefillStatus? {
        guard let prescriptionId, !prescriptionId.isEmpty else { return nil }
        if let medication = medications.first(where: { $0.id == prescriptionId }),
           let open = medication.refillRequestOpen {
            // `false` is an answer, not a miss: falling through to the refills
            // page here would let a row the patient has just cancelled — kept
            // by `load()` when only that fetch failed — hide the button and
            // tell them a withdrawn request is still with their care team.
            // The opposite staleness merely costs a 400 the patient is then
            // told about, so this is the safer way to be wrong.
            guard open else { return nil }
            let status = RefillStatus(wire: medication.refillRequestStatus)
            return status.isOpen ? status : .requested
        }
        return refills.first { $0.prescriptionId == prescriptionId && $0.statusEnum.isOpen }?
            .statusEnum
    }


    /// The backend says two different things: a REQUESTED refill is awaiting
    /// review, a PAUSED one was deliberately held with a follow-up promised —
    /// and the second is the message that explains the delay.
    static func openRefillMessageKey(_ status: RefillStatus) -> String {
        status == .paused ? "refill_on_hold" : "refill_already_open"
    }

    func cancelRefill(id: String) async {
        let _: RefillDTO? = try? await APIClient.shared.put(APIEndpoints.cancelRefill(id: id))
        await load()
    }
}
