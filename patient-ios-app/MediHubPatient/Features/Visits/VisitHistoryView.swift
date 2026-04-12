import SwiftUI

struct VisitHistoryView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = VisitHistoryViewModel()
    @State private var selectedTab = 0

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.loadAll() }
        } else {
            content
                .task { await vm.loadAll() }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                Text("visits_title".localized).tag(0)
                Text("summaries".localized).tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            if vm.isLoading {
                ProgressView("Loading…")
            } else if selectedTab == 0 {
                visitsList
            } else {
                summariesList
            }
        }
        .navigationTitle("visit_history".localized)
        .refreshable { await vm.loadAll() }
    }

    // MARK: - Visits Tab

    private var visitsList: some View {
        Group {
            if vm.encounters.isEmpty {
                ContentUnavailableView("No Visits", systemImage: "building.2.fill",
                                       description: Text("No visit history found."))
            } else {
                List(vm.encounters) { encounter in
                    EncounterRowView(encounter: encounter)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: - Summaries Tab

    private var summariesList: some View {
        Group {
            if vm.summaries.isEmpty {
                ContentUnavailableView("No Summaries", systemImage: "doc.text",
                                       description: Text("No after-visit summaries available."))
            } else {
                List(vm.summaries) { summary in
                    AfterVisitSummaryCard(summary: summary)
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

struct EncounterRowView: View {
    let encounter: EncounterDTO
    
    private var statusText: String {
        (encounter.status ?? "—")
            .replacingOccurrences(of: "_", with: " ")
            .capitalized
    }
    
    private var statusColor: String {
        switch encounter.status?.uppercased() {
        case "COMPLETED": return "green"
        case "CANCELLED": return "red"
        case "IN_PROGRESS", "TRIAGE", "WAITING_FOR_PHYSICIAN": return "blue"
        default: return "gray"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                if let type = encounter.type {
                    Text(type.capitalized).font(.caption).bold()
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.1))
                        .foregroundColor(.accentColor)
                        .cornerRadius(4)
                }
                Spacer()
                StatusBadge(text: statusText, color: statusColor)
            }
            Text(encounter.providerName ?? "Provider").font(.headline)
            if let dept = encounter.department {
                Text(dept).font(.subheadline).foregroundColor(.secondary)
            }
            if let complaint = encounter.chiefComplaint {
                Text(complaint).font(.caption).foregroundColor(.secondary).lineLimit(2)
            }
            if let date = encounter.date {
                Text(Self.formatDate(date)).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    /// Format ISO-8601 datetime to "Apr 8, 2026"
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
        // Try plain date "yyyy-MM-dd"
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        df.locale = Locale(identifier: "en_US_POSIX")
        if let date = df.date(from: String(iso.prefix(10))) {
            df.dateFormat = "MMM d, yyyy"
            return df.string(from: date)
        }
        return String(iso.prefix(10))
    }
}

// MARK: - After Visit Summary Card

struct AfterVisitSummaryCard: View {
    let summary: AfterVisitSummaryDTO
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Header
            Button(action: { withAnimation { isExpanded.toggle() } }) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(summary.dischargingProviderName ?? "Provider").font(.headline)
                        if let hospital = summary.hospitalName {
                            Text(hospital).font(.caption).foregroundColor(.secondary)
                        }
                        if let date = summary.dischargeDate ?? summary.dischargeTime {
                            Text(Self.formatDate(date)).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundColor(.secondary)
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                Divider()

                if let diag = summary.dischargeDiagnosis, !diag.isEmpty {
                    DetailRow(label: "Diagnosis", value: diag)
                }

                if let condition = summary.dischargeCondition {
                    DetailRow(label: "Condition at Discharge", value: condition)
                }

                if let course = summary.hospitalCourse {
                    DetailRow(label: "Hospital Course", value: course)
                }

                if let instructions = summary.followUpInstructions {
                    DetailRow(label: "Follow-up Instructions", value: instructions)
                }

                if let activity = summary.activityRestrictions {
                    DetailRow(label: "Activity Restrictions", value: activity)
                }

                if let diet = summary.dietInstructions {
                    DetailRow(label: "Diet Instructions", value: diet)
                }

                if let warnings = summary.warningSigns {
                    DetailRow(label: "Warning Signs", value: warnings)
                }

                if let meds = summary.medicationReconciliation, !meds.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Medications").font(.caption).bold().foregroundColor(.secondary)
                        ForEach(meds.indices, id: \.self) { i in
                            let med = meds[i]
                            Text("• \([med.medicationName, med.dosage, med.frequency, med.action].compactMap { $0 }.joined(separator: " — "))")
                                .font(.subheadline)
                        }
                    }
                }

                if let appts = summary.followUpAppointments, !appts.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Follow-up Appointments").font(.caption).bold().foregroundColor(.secondary)
                        ForEach(appts.indices, id: \.self) { i in
                            let appt = appts[i]
                            Text("• \([appt.providerName, appt.specialty, appt.appointmentDate].compactMap { $0 }.joined(separator: " — "))")
                                .font(.subheadline)
                        }
                    }
                }

                if let notes = summary.additionalNotes, !notes.isEmpty {
                    DetailRow(label: "Additional Notes", value: notes)
                }
            }
        }
        .padding(.vertical, 4)
    }

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
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        df.locale = Locale(identifier: "en_US_POSIX")
        if let date = df.date(from: String(iso.prefix(10))) {
            df.dateFormat = "MMM d, yyyy"
            return df.string(from: date)
        }
        return String(iso.prefix(10))
    }
}

private struct DetailRow: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).bold().foregroundColor(.secondary)
            Text(value).font(.subheadline)
        }
    }
}

@MainActor
final class VisitHistoryViewModel: ObservableObject {
    @Published var encounters: [EncounterDTO] = []
    @Published var summaries: [AfterVisitSummaryDTO] = []
    @Published var isLoading = false

    func loadAll() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                self.encounters = await (try? APIClient.shared.get(APIEndpoints.encounters)) ?? []
            }
            group.addTask { @MainActor in
                self.summaries = await (try? APIClient.shared.get(APIEndpoints.afterVisitSummaries)) ?? []
            }
        }
        isLoading = false
    }
}
