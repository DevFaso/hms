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
                List(vm.members) { member in
                    CareTeamMemberRow(member: member)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("care_team_title".localized)
        .refreshable { await vm.load() }
    }
}

struct CareTeamMemberRow: View {
    let member: CareTeamMemberDTO
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "person.crop.circle.fill")
                .font(.largeTitle)
                .foregroundColor(member.isPrimary == true ? .accentColor : .secondary)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(member.name ?? "Doctor").font(.headline)
                    if member.isPrimary == true {
                        StatusBadge(text: "Primary", color: "blue")
                    }
                }
                if let spec = member.specialty { Text(spec).font(.subheadline).foregroundColor(.secondary) }
                if let role = member.role { Text(role).font(.caption).foregroundColor(.secondary) }
                if let email = member.email { Text(email).font(.caption2).foregroundColor(.secondary) }
            }
            Spacer()
            if let phone = member.phone {
                Link(destination: URL(string: "tel:\(phone)")!) {
                    Image(systemName: "phone.fill").foregroundColor(.green)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class CareTeamViewModel: ObservableObject {
    @Published var members: [CareTeamMemberDTO] = []
    @Published var isLoading = false

    func load() async {
        isLoading = true
        let dto: CareTeamDTO? = try? await APIClient.shared.get(APIEndpoints.careTeam)
        members = dto?.members ?? []
        // Fallback: some backends return array directly
        if members.isEmpty {
            members = await (try? APIClient.shared.get(APIEndpoints.careTeam)) ?? []
        }
        isLoading = false
    }
}
