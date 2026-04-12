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
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(vm.summaries) { summary in
                            VisitSummaryCard(summary: summary)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .background(Color(.systemGroupedBackground))
            }
        }
        .navigationTitle("visit_summaries_title".localized)
        .refreshable { await vm.load() }
    }
}

// MARK: - Summary Card

struct VisitSummaryCard: View {
    let summary: AfterVisitSummaryDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {

            // ── Header ──
            headerSection
                .padding(16)

            Divider()

            // ── Body ──
            VStack(alignment: .leading, spacing: 16) {

                // Encounter type badge
                if let encounterType = summary.encounterType, !encounterType.isEmpty {
                    Text(encounterType.replacingOccurrences(of: "_", with: " "))
                        .font(.caption.weight(.semibold))
                        .textCase(.uppercase)
                        .tracking(0.5)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.blue.opacity(0.1))
                        .foregroundColor(.blue)
                        .clipShape(Capsule())
                }

                // Diagnosis
                if let diag = summary.dischargeDiagnosis, !diag.isEmpty {
                    iconSection(icon: "stethoscope", iconColor: .red, title: "Diagnosis", body: diag)
                }

                // Treatment Summary / Hospital Course
                if let course = summary.hospitalCourse, !course.isEmpty {
                    iconSection(icon: "heart.text.clipboard", iconColor: .purple, title: "Treatment Summary", body: course)
                }

                // Disposition + Condition row
                if summary.disposition != nil || summary.dischargeCondition != nil {
                    HStack(spacing: 12) {
                        if let disposition = summary.disposition, !disposition.isEmpty {
                            infoPill(
                                icon: "arrow.right.circle.fill",
                                color: .indigo,
                                label: "Disposition",
                                value: disposition.replacingOccurrences(of: "_", with: " ").capitalized
                            )
                        }
                        if let condition = summary.dischargeCondition, !condition.isEmpty {
                            infoPill(
                                icon: "waveform.path.ecg",
                                color: .teal,
                                label: "Condition",
                                value: condition.capitalized
                            )
                        }
                    }
                }

                // Medications
                if let meds = summary.medicationReconciliation, !meds.isEmpty {
                    medicationsSection(meds)
                }

                // Follow-up instructions
                if let instructions = summary.followUpInstructions, !instructions.isEmpty {
                    iconSection(icon: "calendar.badge.clock", iconColor: .orange, title: "Follow-up Instructions", body: instructions)
                }

                // Activity restrictions
                if let restrictions = summary.activityRestrictions, !restrictions.isEmpty {
                    iconSection(icon: "figure.walk", iconColor: .cyan, title: "Activity Restrictions", body: restrictions)
                }

                // Diet instructions
                if let diet = summary.dietInstructions, !diet.isEmpty {
                    iconSection(icon: "fork.knife", iconColor: .green, title: "Diet Instructions", body: diet)
                }

                // Wound care
                if let wound = summary.woundCareInstructions, !wound.isEmpty {
                    iconSection(icon: "bandage.fill", iconColor: .pink, title: "Wound Care", body: wound)
                }

                // Warning signs
                if let warnings = summary.warningSigns, !warnings.isEmpty {
                    warningSection(warnings)
                }

                // Patient education
                if let education = summary.patientEducationProvided, !education.isEmpty {
                    iconSection(icon: "book.fill", iconColor: .blue, title: "Patient Education", body: education)
                }

                // Additional notes
                if let notes = summary.additionalNotes, !notes.isEmpty {
                    iconSection(icon: "note.text", iconColor: .secondary, title: "Additional Notes", body: notes)
                }

                // Follow-up appointments
                if let appts = summary.followUpAppointments, !appts.isEmpty {
                    appointmentsSection(appts)
                }

            }
            .padding(16)

        }
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: Color.black.opacity(0.06), radius: 8, x: 0, y: 2)
    }

    // MARK: - Header

    private var headerSection: some View {
        HStack(alignment: .top) {
            // Provider avatar circle
            ZStack {
                Circle()
                    .fill(Color.blue.opacity(0.12))
                    .frame(width: 44, height: 44)
                Image(systemName: "person.fill")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(.blue)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(summary.dischargingProviderName ?? "Provider")
                    .font(.headline)
                if let hospital = summary.hospitalName {
                    Text(hospital)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.leading, 4)

            Spacer()

            VStack(alignment: .trailing, spacing: 6) {
                if let date = summary.dischargeDate ?? summary.dischargeTime {
                    Text(formatDate(String(date.prefix(10))))
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color.blue.opacity(0.1))
                        .foregroundColor(.blue)
                        .clipShape(Capsule())
                }
                statusBadge
            }
        }
    }

    private var statusBadge: some View {
        let isFinalized = summary.isFinalized == true
        return Text(isFinalized ? "Finalized" : "Draft")
            .font(.system(size: 10, weight: .bold))
            .textCase(.uppercase)
            .tracking(0.3)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(isFinalized ? Color.green.opacity(0.12) : Color.orange.opacity(0.12))
            .foregroundColor(isFinalized ? .green : .orange)
            .clipShape(Capsule())
    }

    // MARK: - Section Builders

    @ViewBuilder
    private func iconSection(icon: String, iconColor: Color, title: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(iconColor)
                .frame(width: 20, alignment: .center)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
                    .textCase(.uppercase)
                    .tracking(0.3)
                Text(body)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private func infoPill(icon: String, color: Color, label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 10))
                    .foregroundColor(color)
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.secondary)
                    .textCase(.uppercase)
            }
            Text(value)
                .font(.subheadline.weight(.medium))
                .foregroundColor(color)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    @ViewBuilder
    private func medicationsSection(_ meds: [MedicationReconciliationDTO]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "pill.fill")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.blue)
                Text("Medications")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
                    .textCase(.uppercase)
                    .tracking(0.3)
                Spacer()
                Text("\(meds.count)")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.blue.opacity(0.12))
                    .foregroundColor(.blue)
                    .clipShape(Capsule())
            }

            VStack(spacing: 0) {
                ForEach(Array(meds.enumerated()), id: \.offset) { idx, med in
                    HStack(spacing: 10) {
                        Circle()
                            .fill(Color.blue.opacity(0.15))
                            .frame(width: 28, height: 28)
                            .overlay(
                                Image(systemName: "pill.fill")
                                    .font(.system(size: 11))
                                    .foregroundColor(.blue)
                            )

                        VStack(alignment: .leading, spacing: 1) {
                            Text(med.medicationName ?? "Unknown")
                                .font(.subheadline.weight(.medium))
                            HStack(spacing: 6) {
                                if let dosage = med.dosage {
                                    Text(dosage)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                if let freq = med.frequency {
                                    Text("•")
                                        .font(.caption)
                                        .foregroundColor(.secondary.opacity(0.5))
                                    Text(freq)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }

                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 10)

                    if idx < meds.count - 1 {
                        Divider().padding(.leading, 48)
                    }
                }
            }
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    @ViewBuilder
    private func warningSection(_ warnings: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 18))
                .foregroundColor(.white)
                .padding(6)
                .background(Color.orange)
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text("Warning Signs")
                    .font(.caption.weight(.bold))
                    .foregroundColor(.orange)
                    .textCase(.uppercase)
                    .tracking(0.3)
                Text(warnings)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.orange.opacity(0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color.orange.opacity(0.2), lineWidth: 1)
                )
        )
    }

    @ViewBuilder
    private func appointmentsSection(_ appts: [FollowUpAppointmentDTO]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "calendar.badge.clock")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.orange)
                Text("Follow-up Appointments")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
                    .textCase(.uppercase)
                    .tracking(0.3)
            }

            ForEach(Array(appts.enumerated()), id: \.offset) { idx, appt in
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(Color.orange.opacity(0.6))
                        .frame(width: 3, height: 30)

                    VStack(alignment: .leading, spacing: 1) {
                        if let name = appt.providerName {
                            Text(name).font(.subheadline.weight(.medium))
                        }
                        if let date = appt.appointmentDate {
                            Text(formatDate(String(date.prefix(10))))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        if let notes = appt.notes {
                            Text(notes)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    // MARK: - Helpers

    private func formatDate(_ iso: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: iso) else { return iso }
        formatter.dateFormat = "MMM d, yyyy"
        return formatter.string(from: date)
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
