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
                StatusBadge(text: encounter.status?.capitalized ?? "—",
                            color: encounter.status?.uppercased() == "COMPLETED" ? "gray" : "blue")
            }
            Text(encounter.providerName ?? "Provider").font(.headline)
            if let dept = encounter.department {
                Text(dept).font(.subheadline).foregroundColor(.secondary)
            }
            if let complaint = encounter.chiefComplaint {
                Text(complaint).font(.caption).foregroundColor(.secondary).lineLimit(2)
            }
            if let date = encounter.date {
                Text(date).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
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
                        Text(summary.providerName ?? "Provider").font(.headline)
                        if let dept = summary.department {
                            Text(dept).font(.caption).foregroundColor(.secondary)
                        }
                        Text(summary.encounterDate ?? "").font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundColor(.secondary)
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                Divider()

                if let complaint = summary.chiefComplaint {
                    DetailRow(label: "Chief Complaint", value: complaint)
                }

                if let diagnoses = summary.diagnoses, !diagnoses.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Diagnoses").font(.caption).bold().foregroundColor(.secondary)
                        ForEach(diagnoses, id: \.self) { d in
                            Text("• \(d)").font(.subheadline)
                        }
                    }
                }

                if let treatment = summary.treatmentSummary {
                    DetailRow(label: "Treatment", value: treatment)
                }

                if let instructions = summary.instructions {
                    DetailRow(label: "Instructions", value: instructions)
                }

                if let meds = summary.medications, !meds.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Medications").font(.caption).bold().foregroundColor(.secondary)
                        ForEach(meds, id: \.self) { m in
                            Text("• \(m)").font(.subheadline)
                        }
                    }
                }

                if let followUp = summary.followUpDate {
                    DetailRow(label: "Follow-Up", value: followUp)
                }
            }
        }
        .padding(.vertical, 4)
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
