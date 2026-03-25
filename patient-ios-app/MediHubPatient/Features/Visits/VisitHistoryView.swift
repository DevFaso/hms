import SwiftUI

struct VisitHistoryView: View {
    @StateObject private var vm = VisitHistoryViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.encounters.isEmpty { ProgressView("Loading visits…") }
                else if vm.encounters.isEmpty {
                    ContentUnavailableView("No Visits", systemImage: "building.2.fill",
                        description: Text("No visit history found."))
                } else {
                    List(vm.encounters) { encounter in
                        NavigationLink(value: encounter) {
                            EncounterRowView(encounter: encounter)
                        }
                    }
                    .listStyle(.insetGrouped)
                    .navigationDestination(for: EncounterDTO.self) { enc in
                        AfterVisitSummaryView(encounterId: enc.id)
                    }
                }
            }
            .navigationTitle("Visit History")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

struct EncounterRowView: View {
    let encounter: EncounterDTO
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(encounter.doctorName ?? "Doctor").font(.headline)
                Spacer()
                StatusBadge(text: encounter.status?.capitalized ?? "—",
                            color: encounter.status?.uppercased() == "COMPLETED" ? "gray" : "blue")
            }
            if let dept = encounter.departmentName { Text(dept).font(.subheadline).foregroundColor(.secondary) }
            if let date = encounter.encounterDate { Text(date).font(.caption2).foregroundColor(.secondary) }
            if let reason = encounter.reason { Text(reason).font(.caption).foregroundColor(.secondary).lineLimit(1) }
        }
        .padding(.vertical, 4)
    }
}

struct AfterVisitSummaryView: View {
    let encounterId: Int?
    @StateObject private var vm = AfterVisitViewModel()

    var body: some View {
        Group {
            if vm.isLoading { ProgressView("Loading summary…") }
            else if let summary = vm.summary {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let diag = summary.diagnosis {
                            SectionCard(title: "Diagnosis", icon: "stethoscope") { Text(diag) }
                        }
                        if let instructions = summary.followUpInstructions {
                            SectionCard(title: "Follow-Up Instructions", icon: "list.clipboard") { Text(instructions) }
                        }
                        if let meds = summary.medications, !meds.isEmpty {
                            SectionCard(title: "Medications Prescribed", icon: "pill.fill") {
                                ForEach(meds, id: \.self) { Text("• \($0)") }
                            }
                        }
                        if let restrictions = summary.restrictions {
                            SectionCard(title: "Restrictions", icon: "exclamationmark.triangle") { Text(restrictions) }
                        }
                    }
                    .padding()
                }
            } else {
                ContentUnavailableView("No Summary Available", systemImage: "doc.text",
                    description: Text("No after-visit summary for this encounter."))
            }
        }
        .navigationTitle("Visit Summary")
        .task { await vm.load(encounterId: encounterId) }
    }
}

@MainActor
final class VisitHistoryViewModel: ObservableObject {
    @Published var encounters: [EncounterDTO] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        encounters = (try? await APIClient.shared.get(APIEndpoints.encounters)) ?? []
        isLoading = false
    }
}

@MainActor
final class AfterVisitViewModel: ObservableObject {
    @Published var summary: DischargeSummaryDTO?
    @Published var isLoading = false

    func load(encounterId: Int?) async {
        isLoading = true
        let summaries: [DischargeSummaryDTO] = (try? await APIClient.shared.get(APIEndpoints.afterVisitSummaries)) ?? []
        summary = summaries.first(where: { $0.encounterId == encounterId })
        isLoading = false
    }
}
