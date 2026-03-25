import SwiftUI

struct CareTeamView: View {
    @StateObject private var vm = CareTeamViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading { ProgressView("Loading care team…") }
                else if vm.members.isEmpty {
                    ContentUnavailableView("No Care Team",
                        systemImage: "person.2.fill",
                        description: Text("No care team members assigned."))
                } else {
                    List(vm.members) { member in
                        CareTeamMemberRow(member: member)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Care Team")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
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
                if let hospital = member.hospitalName { Text(hospital).font(.caption2).foregroundColor(.secondary) }
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
            members = (try? await APIClient.shared.get(APIEndpoints.careTeam)) ?? []
        }
        isLoading = false
    }
}
