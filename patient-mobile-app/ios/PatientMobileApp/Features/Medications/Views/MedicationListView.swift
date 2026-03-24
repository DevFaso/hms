import SwiftUI

struct MedicationListView: View {
    @StateObject private var viewModel = MedicationViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $viewModel.selectedSegment) {
                    Text("Medications").tag(0)
                    Text("Prescriptions").tag(1)
                    Text("Refills").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 8)

                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading medications...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.load() } }
                } else {
                    switch viewModel.selectedSegment {
                    case 0: medicationsList
                    case 1: prescriptionsList
                    default: refillsList
                    }
                }
            }
            .navigationTitle("Medications")
            .refreshable { await viewModel.load() }
            .task { await viewModel.load() }
        }
    }

    // MARK: - Medications Tab

    private var medicationsList: some View {
        Group {
            if viewModel.medications.isEmpty {
                HMSEmptyState(icon: "pill", title: "No Medications", message: "Your active medications will appear here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.medications) { med in
                            NavigationLink(destination: MedicationDetailView(medication: med)) {
                                MedicationCard(medication: med)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    // MARK: - Prescriptions Tab

    private var prescriptionsList: some View {
        Group {
            if viewModel.prescriptions.isEmpty {
                HMSEmptyState(icon: "doc.text", title: "No Prescriptions", message: "Your prescriptions will appear here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.prescriptions) { rx in
                            PrescriptionCard(prescription: rx, onRefill: {
                                Task { await viewModel.requestRefill(prescriptionId: rx.id) }
                            })
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    // MARK: - Refills Tab

    private var refillsList: some View {
        Group {
            if viewModel.refills.isEmpty {
                HMSEmptyState(icon: "arrow.clockwise", title: "No Refills", message: "Your refill requests will appear here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.refills) { refill in
                            RefillCard(refill: refill, onCancel: {
                                Task { await viewModel.cancelRefill(id: refill.id) }
                            })
                        }
                    }
                    .padding(16)
                }
            }
        }
    }
}

// MARK: - Medication Card

private struct MedicationCard: View {
    let medication: MedicationDTO

    var body: some View {
        HMSCard {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(medication.name ?? "Unknown Medication")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    if let dosage = medication.dosage {
                        Text(dosage)
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextSecondary)
                    }
                    if let frequency = medication.frequency {
                        Label(frequency, systemImage: "clock")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextTertiary)
                    }
                }
                Spacer()
                HMSStatusBadge(
                    text: medication.status ?? "Unknown",
                    color: medication.isActive ? .hmsSuccess : .hmsTextSecondary
                )
            }
        }
    }
}

// MARK: - Medication Detail

struct MedicationDetailView: View {
    let medication: MedicationDTO

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HMSCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(medication.name ?? "Medication")
                                .font(.hmsTitle3)
                                .foregroundColor(.hmsTextPrimary)
                            if let generic = medication.genericName {
                                Text(generic)
                                    .font(.hmsCaption)
                                    .foregroundColor(.hmsTextTertiary)
                            }
                        }
                        Spacer()
                        HMSStatusBadge(
                            text: medication.status ?? "Unknown",
                            color: medication.isActive ? .hmsSuccess : .hmsTextSecondary
                        )
                    }
                }

                HMSCard {
                    VStack(alignment: .leading, spacing: 12) {
                        if let dosage = medication.dosage {
                            DetailItem(icon: "pills", label: "Dosage", value: dosage)
                        }
                        if let frequency = medication.frequency {
                            DetailItem(icon: "clock", label: "Frequency", value: frequency)
                        }
                        if let route = medication.route {
                            DetailItem(icon: "arrow.right.circle", label: "Route", value: route)
                        }
                        if let start = medication.startDate {
                            DetailItem(icon: "calendar", label: "Start Date", value: start)
                        }
                        if let end = medication.endDate {
                            DetailItem(icon: "calendar.badge.clock", label: "End Date", value: end)
                        }
                        if let prescriber = medication.prescribedBy {
                            DetailItem(icon: "stethoscope", label: "Prescribed By", value: prescriber)
                        }
                    }
                }

                if let instructions = medication.instructions {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Instructions")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(instructions)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextPrimary)
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DetailItem: View {
    let icon: String
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(.hmsPrimary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextTertiary)
                Text(value)
                    .font(.hmsBody)
                    .foregroundColor(.hmsTextPrimary)
            }
        }
    }
}

// MARK: - Prescription Card

private struct PrescriptionCard: View {
    let prescription: PrescriptionDTO
    let onRefill: () -> Void

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(prescription.medicationName ?? "Prescription")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    Spacer()
                    HMSStatusBadge(
                        text: prescription.status ?? "Unknown",
                        color: prescriptionStatusColor(prescription.status)
                    )
                }

                if let dosage = prescription.dosage {
                    Text(dosage)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }

                HStack {
                    if let refills = prescription.refillsRemaining {
                        Label("\(refills) refills remaining", systemImage: "arrow.clockwise")
                            .font(.hmsCaption)
                            .foregroundColor(refills > 0 ? .hmsSuccess : .hmsError)
                    }
                    Spacer()
                    if (prescription.refillsRemaining ?? 0) > 0 && prescription.status?.uppercased() == "ACTIVE" {
                        Button("Request Refill", action: onRefill)
                            .font(.hmsCaptionMedium)
                            .foregroundColor(.hmsPrimary)
                    }
                }
            }
        }
    }

    private func prescriptionStatusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "ACTIVE": return .hmsSuccess
        case "EXPIRED": return .hmsError
        case "COMPLETED": return .hmsInfo
        default: return .hmsTextSecondary
        }
    }
}

// MARK: - Refill Card

private struct RefillCard: View {
    let refill: RefillDTO
    let onCancel: () -> Void

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(refill.medicationName ?? "Refill Request")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    Spacer()
                    HMSStatusBadge(
                        text: refill.status ?? "Unknown",
                        color: refillStatusColor(refill.status)
                    )
                }

                if let requested = refill.requestedDate {
                    Label("Requested: \(requested)", systemImage: "calendar")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }

                if let completed = refill.completedDate {
                    Label("Completed: \(completed)", systemImage: "checkmark.circle")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsSuccess)
                }

                if refill.status?.uppercased() == "PENDING" {
                    HStack {
                        Spacer()
                        Button("Cancel", action: onCancel)
                            .font(.hmsCaptionMedium)
                            .foregroundColor(.hmsError)
                    }
                }
            }
        }
    }

    private func refillStatusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "PENDING": return .hmsWarning
        case "APPROVED", "COMPLETED", "READY": return .hmsSuccess
        case "DENIED", "CANCELLED": return .hmsError
        default: return .hmsTextSecondary
        }
    }
}
