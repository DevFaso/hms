import SwiftUI

struct ConsultationsView: View {
    @StateObject private var viewModel = ConsultationsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.consultations.isEmpty {
                    HMSLoadingView(message: "Loading consultations...")
                } else if let error = viewModel.error, viewModel.consultations.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadConsultations() } }
                } else if viewModel.consultations.isEmpty {
                    HMSEmptyState(
                        icon: "person.2.circle",
                        title: "No Consultations",
                        message: "You don't have any consultations."
                    )
                } else {
                    consultationList
                }
            }
            .navigationTitle("Consultations")
            .refreshable { await viewModel.loadConsultations() }
            .task { await viewModel.loadConsultations() }
        }
    }

    private var consultationList: some View {
        List(viewModel.consultations) { consultation in
            NavigationLink {
                ConsultationDetailView(consultation: consultation)
            } label: {
                consultationRow(consultation)
            }
        }
        .listStyle(.plain)
    }

    private func consultationRow(_ c: ConsultationDto) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(c.reason ?? c.consultationType ?? "Consultation")
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                HMSStatusBadge(status: c.status ?? "unknown")
            }
            if let consultant = c.consultantDoctorName {
                Label(consultant, systemImage: "stethoscope")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let specialty = c.consultantSpecialty ?? c.consultantDepartment {
                Label(specialty, systemImage: "building.2")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                if let date = c.scheduledDate ?? c.requestedDate {
                    Label(String(date.prefix(10)), systemImage: "calendar")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let priority = c.priority {
                    Text(priority.capitalized)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(priorityColor(priority).opacity(0.15))
                        .foregroundStyle(priorityColor(priority))
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func priorityColor(_ priority: String) -> Color {
        switch priority.lowercased() {
        case "urgent", "emergency", "high": return .red
        case "medium", "normal": return .orange
        case "low", "routine": return .green
        default: return .secondary
        }
    }
}

// MARK: - Detail View

struct ConsultationDetailView: View {
    let consultation: ConsultationDto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(consultation.reason ?? "Consultation")
                                .font(.title2.bold())
                            Spacer()
                            HMSStatusBadge(status: consultation.status ?? "unknown")
                        }
                        if let type = consultation.consultationType {
                            Label(type, systemImage: "tag")
                                .foregroundStyle(.secondary)
                        }
                        if let priority = consultation.priority {
                            Label(priority.capitalized, systemImage: "exclamationmark.circle")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Requesting Doctor
                if consultation.requestingDoctorName != nil || consultation.requestingDepartment != nil {
                    HMSSectionHeader(title: "Requested By")
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            if let name = consultation.requestingDoctorName {
                                Label(name, systemImage: "stethoscope")
                            }
                            if let dept = consultation.requestingDepartment {
                                Label(dept, systemImage: "building.2")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                // Consultant
                if consultation.consultantDoctorName != nil {
                    HMSSectionHeader(title: "Consultant")
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            if let name = consultation.consultantDoctorName {
                                Label(name, systemImage: "stethoscope")
                            }
                            if let specialty = consultation.consultantSpecialty {
                                Label(specialty, systemImage: "cross.case")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            if let dept = consultation.consultantDepartment {
                                Label(dept, systemImage: "building.2")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                // Dates
                HMSSectionHeader(title: "Timeline")
                HMSCard {
                    VStack(alignment: .leading, spacing: 6) {
                        if let requested = consultation.requestedDate {
                            Label("Requested: \(String(requested.prefix(10)))", systemImage: "calendar")
                                .font(.subheadline)
                        }
                        if let scheduled = consultation.scheduledDate {
                            Label("Scheduled: \(String(scheduled.prefix(16)))", systemImage: "calendar.badge.clock")
                                .font(.subheadline)
                        }
                        if let completed = consultation.completedDate {
                            Label("Completed: \(String(completed.prefix(10)))", systemImage: "checkmark.circle")
                                .font(.subheadline)
                                .foregroundStyle(.green)
                        }
                    }
                }

                // Findings & Recommendations
                if let findings = consultation.findings, !findings.isEmpty {
                    HMSSectionHeader(title: "Findings")
                    HMSCard { Text(findings).font(.body) }
                }

                if let diagnosis = consultation.diagnosis, !diagnosis.isEmpty {
                    HMSSectionHeader(title: "Diagnosis")
                    HMSCard { Text(diagnosis).font(.body) }
                }

                if let recommendations = consultation.recommendations, !recommendations.isEmpty {
                    HMSSectionHeader(title: "Recommendations")
                    HMSCard { Text(recommendations).font(.body) }
                }

                if let notes = consultation.notes, !notes.isEmpty {
                    HMSSectionHeader(title: "Notes")
                    HMSCard { Text(notes).font(.body) }
                }

                // Follow-up
                if consultation.followUpRequired == true {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Label("Follow-up Required", systemImage: "arrow.uturn.forward")
                                .font(.subheadline.bold())
                                .foregroundStyle(.orange)
                            if let date = consultation.followUpDate {
                                Label(String(date.prefix(10)), systemImage: "calendar")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Consultation Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}
