import SwiftUI

struct CareTeamView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = CareTeamViewModel()

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
            if vm.isLoading { ProgressView("loading".localized) }
            else if vm.members.isEmpty {
                ContentUnavailableView("no_care_team".localized,
                                       systemImage: "person.2.fill",
                                       description: Text("no_care_team_desc".localized))
            } else {
                List(vm.members) { entry in
                    PrimaryCareRow(entry: entry)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("care_team_title".localized)
        .refreshable { await vm.load() }
    }
}

struct PrimaryCareRow: View {
    let entry: PrimaryCareEntry
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "person.crop.circle.fill")
                .font(.largeTitle)
                .foregroundColor(entry.current ? .accentColor : .secondary)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(entry.displayName).font(.headline)
                    if entry.current {
                        StatusBadge(text: "Current", color: "blue")
                    } else {
                        StatusBadge(text: "Past", color: "gray")
                    }
                }
                if let hospital = entry.hospitalName {
                    Text(hospital).font(.subheadline).foregroundColor(.secondary)
                }
                if let start = entry.startDate {
                    HStack(spacing: 4) {
                        Text("Since \(start)").font(.caption).foregroundColor(.secondary)
                        if let end = entry.endDate {
                            Text("— \(end)").font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class CareTeamViewModel: ObservableObject {
    @Published var members: [PrimaryCareEntry] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        if let dto: CareTeamDTO = try? await APIClient.shared.get(APIEndpoints.careTeam) {
            members = dto.allMembers
        } else {
            members = []
        }
        isLoading = false
    }
}
