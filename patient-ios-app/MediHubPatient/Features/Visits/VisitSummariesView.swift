import SwiftUI

// MARK: - Visit Summaries (After-Visit Summaries) — Read-only

struct VisitSummariesView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = VisitSummariesViewModel()

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
            if vm.isLoading, vm.summaries.isEmpty {
                ProgressView("loading".localized)
            } else if vm.summaries.isEmpty {
                ContentUnavailableView(
                    "no_summaries".localized,
                    systemImage: "doc.text.fill",
                    description: Text("no_summaries_desc".localized)
                )
            } else {
                List(vm.summaries) { summary in
                    VisitSummaryRow(summary: summary)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("visit_summaries_title".localized)
        .refreshable { await vm.load() }
    }
}

// MARK: - Summary Row

struct VisitSummaryRow: View {
    let summary: AfterVisitSummaryDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Provider + date header
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(summary.dischargingProviderName ?? "Provider")
                        .font(.subheadline.weight(.semibold))
                    if let hospital = summary.hospitalName {
                        Text(hospital)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                if let date = summary.dischargeDate ?? summary.dischargeTime {
                    Text(String(date.prefix(10)))
                        .font(.caption2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.accentColor.opacity(0.1))
                        .foregroundColor(.accentColor)
                        .clipShape(Capsule())
                }
            }

            // Encounter type
            if let encounterType = summary.encounterType, !encounterType.isEmpty {
                sectionField("Encounter Type", value: encounterType)
            }

            // Diagnosis
            if let diag = summary.dischargeDiagnosis, !diag.isEmpty {
                sectionField("Diagnosis", value: diag)
            }

            // Treatment Summary / Hospital Course
            if let course = summary.hospitalCourse, !course.isEmpty {
                sectionField("Treatment Summary", value: course)
            }

            // Disposition
            if let disposition = summary.disposition, !disposition.isEmpty {
                sectionField("Disposition", value: disposition)
            }

            // Discharge condition
            if let condition = summary.dischargeCondition, !condition.isEmpty {
                sectionField("Condition at Discharge", value: condition)
            }

            // Medications
            if let meds = summary.medicationReconciliation, !meds.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Medications")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.secondary)
                    ForEach(meds.indices, id: \.self) { idx in
                        let med = meds[idx]
                        HStack(spacing: 4) {
                            Image(systemName: "pill.fill")
                                .font(.caption2)
                                .foregroundColor(.blue)
                            Text([med.medicationName, med.dosage, med.frequency]
                                .compactMap { $0 }
                                .joined(separator: " · "))
                                .font(.caption)
                        }
                    }
                }
            }

            // Follow-up instructions
            if let instructions = summary.followUpInstructions, !instructions.isEmpty {
                sectionField("Follow-up Instructions", value: instructions)
            }

            // Activity restrictions
            if let restrictions = summary.activityRestrictions, !restrictions.isEmpty {
                sectionField("Activity Restrictions", value: restrictions)
            }

            // Diet instructions
            if let diet = summary.dietInstructions, !diet.isEmpty {
                sectionField("Diet Instructions", value: diet)
            }

            // Wound care
            if let wound = summary.woundCareInstructions, !wound.isEmpty {
                sectionField("Wound Care", value: wound)
            }

            // Warning signs
            if let warnings = summary.warningSigns, !warnings.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Warning Signs", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.orange)
                    Text(warnings)
                        .font(.subheadline)
                }
                .padding(8)
                .background(Color.orange.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            // Patient education
            if let education = summary.patientEducationProvided, !education.isEmpty {
                sectionField("Patient Education", value: education)
            }

            // Additional notes
            if let notes = summary.additionalNotes, !notes.isEmpty {
                sectionField("Additional Notes", value: notes)
            }

            // Follow-up appointments
            if let appts = summary.followUpAppointments, !appts.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Follow-up Appointments")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.secondary)
                    ForEach(appts.indices, id: \.self) { idx in
                        let appt = appts[idx]
                        HStack(spacing: 4) {
                            Image(systemName: "calendar.badge.clock")
                                .font(.caption)
                                .foregroundColor(.orange)
                            Text("\(appt.providerName ?? "") \(appt.appointmentDate ?? "")")
                                .font(.caption)
                                .foregroundColor(.orange)
                        }
                    }
                }
            }

            // Status badge
            HStack {
                Spacer()
                Text(summary.isFinalized == true ? "Finalized" : "Draft")
                    .font(.system(size: 10, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(summary.isFinalized == true ? Color.green.opacity(0.12) : Color.orange.opacity(0.12))
                    .foregroundColor(summary.isFinalized == true ? .green : .orange)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func sectionField(_ label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundColor(.secondary)
            Text(value)
                .font(.subheadline)
        }
    }
}

// MARK: - ViewModel

@MainActor
final class VisitSummariesViewModel: ObservableObject {
    @Published var summaries: [AfterVisitSummaryDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        errorMessage = nil
        summaries = await (try? APIClient.shared.get(
            APIEndpoints.afterVisitSummaries,
            queryItems: [
                URLQueryItem(name: "page", value: "0"),
                URLQueryItem(name: "size", value: "50")
            ]
        )) ?? []
        isLoading = false
    }
}
