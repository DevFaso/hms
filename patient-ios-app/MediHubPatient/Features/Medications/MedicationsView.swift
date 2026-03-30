import SwiftUI

struct MedicationsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = MedicationsViewModel()
    @State private var selectedTab = 0
    @State private var refillTarget: PrescriptionDTO?
    @State private var showError = false

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
                case 0:  medicationsList
                case 1:  prescriptionsList
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
                            StatusBadge(text: refill.status?.capitalized ?? "Pending",
                                        color: refillStatusColor(refill.status))
                        }
                        if let dosage = refill.dosage {
                            Text(dosage).font(.subheadline).foregroundColor(.secondary)
                        }
                        if let pharmacy = refill.preferredPharmacy, !pharmacy.isEmpty {
                            Text("Pharmacy: \(pharmacy)").font(.caption).foregroundColor(.secondary)
                        }
                        if let requested = refill.requestedAt {
                            Text("Requested: \(requested)").font(.caption2).foregroundColor(.secondary)
                        }
                        if let completed = refill.completedAt {
                            Text("Completed: \(completed)").font(.caption2).foregroundColor(.secondary)
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
        case "COMPLETED", "FILLED": return "green"
        case "PENDING", "REQUESTED": return "yellow"
        case "CANCELLED", "DENIED": return "red"
        default: return "blue"
        }
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
                self.medications = (try? await APIClient.shared.get(
                    APIEndpoints.medications,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask { @MainActor in
                self.prescriptions = (try? await APIClient.shared.get(APIEndpoints.prescriptions)) ?? []
            }
            group.addTask { @MainActor in
                self.refills = (try? await APIClient.shared.get(APIEndpoints.refills)) ?? []
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
