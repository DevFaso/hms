import SwiftUI

struct RecordsView: View {
    @StateObject private var viewModel = RecordsViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $viewModel.selectedSegment) {
                    Text("Visits").tag(0)
                    Text("Summaries").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 8)

                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading records...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.load() } }
                } else {
                    switch viewModel.selectedSegment {
                    case 0: encountersList
                    default: summariesList
                    }
                }
            }
            .navigationTitle("Records")
            .refreshable { await viewModel.load() }
            .task { await viewModel.load() }
        }
    }

    // MARK: - Encounters

    private var encountersList: some View {
        Group {
            if viewModel.encounters.isEmpty {
                HMSEmptyState(icon: "heart.text.clipboard", title: "No Visit History", message: "Your visit records will appear here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.encounters) { encounter in
                            NavigationLink(destination: EncounterDetailView(encounter: encounter)) {
                                EncounterCard(encounter: encounter)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    // MARK: - Visit Summaries

    private var summariesList: some View {
        Group {
            if viewModel.visitSummaries.isEmpty {
                HMSEmptyState(icon: "doc.text", title: "No Summaries", message: "After-visit summaries will appear here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.visitSummaries) { summary in
                            NavigationLink(destination: VisitSummaryDetailView(summary: summary)) {
                                VisitSummaryCard(summary: summary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }
}

// MARK: - Encounter Card

private struct EncounterCard: View {
    let encounter: EncounterDTO

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(encounter.encounterType ?? "Visit")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    Spacer()
                    HMSStatusBadge(
                        text: encounter.status ?? "Unknown",
                        color: encounterStatusColor(encounter.status)
                    )
                }
                if let doctor = encounter.doctorName {
                    Label(doctor, systemImage: "stethoscope")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }
                HStack {
                    Label(encounter.displayDate, systemImage: "calendar")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                    if let dept = encounter.departmentName {
                        Spacer()
                        Label(dept, systemImage: "building.2")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextTertiary)
                    }
                }
                if let complaint = encounter.chiefComplaint {
                    Text(complaint)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                        .lineLimit(2)
                }
            }
        }
    }

    private func encounterStatusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "COMPLETED", "DISCHARGED": return .hmsSuccess
        case "IN_PROGRESS", "ACTIVE": return .hmsInfo
        case "CANCELLED": return .hmsError
        default: return .hmsTextSecondary
        }
    }
}

// MARK: - Encounter Detail

struct EncounterDetailView: View {
    let encounter: EncounterDTO

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HMSCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(encounter.encounterType ?? "Visit")
                                .font(.hmsTitle3)
                                .foregroundColor(.hmsTextPrimary)
                            Text(encounter.displayDate)
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                        Spacer()
                        HMSStatusBadge(
                            text: encounter.status ?? "Unknown",
                            color: .hmsInfo
                        )
                    }
                }

                HMSCard {
                    VStack(alignment: .leading, spacing: 12) {
                        if let doctor = encounter.doctorName {
                            InfoRow(icon: "stethoscope", label: "Doctor", value: doctor)
                        }
                        if let dept = encounter.departmentName {
                            InfoRow(icon: "building.2", label: "Department", value: dept)
                        }
                        if let hospital = encounter.hospitalName {
                            InfoRow(icon: "cross.fill", label: "Hospital", value: hospital)
                        }
                    }
                }

                if let complaint = encounter.chiefComplaint {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Chief Complaint")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(complaint)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextPrimary)
                        }
                    }
                }

                if let diagnosis = encounter.diagnosis {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Diagnosis")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(diagnosis)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextPrimary)
                        }
                    }
                }

                if let notes = encounter.notes {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Notes")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(notes)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextSecondary)
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Visit Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct InfoRow: View {
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

// MARK: - Visit Summary Card

private struct VisitSummaryCard: View {
    let summary: VisitSummaryDTO

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Visit Summary")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    Spacer()
                    if let date = summary.visitDate {
                        Text(date)
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextTertiary)
                    }
                }
                if let doctor = summary.doctorName {
                    Label(doctor, systemImage: "stethoscope")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }
                if let diagnosis = summary.diagnosis {
                    Text(diagnosis)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                        .lineLimit(2)
                }
            }
        }
    }
}

// MARK: - Visit Summary Detail

struct VisitSummaryDetailView: View {
    let summary: VisitSummaryDTO

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("After-Visit Summary")
                            .font(.hmsTitle3)
                            .foregroundColor(.hmsTextPrimary)
                        if let date = summary.visitDate {
                            Label(date, systemImage: "calendar")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                        if let doctor = summary.doctorName {
                            Label(doctor, systemImage: "stethoscope")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                    }
                }

                if let summaryText = summary.summary {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Summary")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(summaryText)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextPrimary)
                        }
                    }
                }

                if let diagnosis = summary.diagnosis {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Diagnosis")
                                .font(.hmsCaptionMedium)
                                .foregroundColor(.hmsTextTertiary)
                            Text(diagnosis)
                                .font(.hmsBody)
                                .foregroundColor(.hmsTextPrimary)
                        }
                    }
                }

                if let instructions = summary.instructions {
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

                if let followUp = summary.followUpDate {
                    HMSCard {
                        HStack {
                            Image(systemName: "calendar.badge.clock")
                                .foregroundColor(.hmsPrimary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Follow-Up Date")
                                    .font(.hmsCaption)
                                    .foregroundColor(.hmsTextTertiary)
                                Text(followUp)
                                    .font(.hmsBodyMedium)
                                    .foregroundColor(.hmsTextPrimary)
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Summary")
        .navigationBarTitleDisplayMode(.inline)
    }
}
