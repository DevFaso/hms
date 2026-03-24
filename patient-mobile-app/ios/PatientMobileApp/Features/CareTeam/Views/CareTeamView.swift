import SwiftUI

struct CareTeamView: View {
    @StateObject private var viewModel = CareTeamViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading care team...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.loadCareTeam() } }
                } else if viewModel.members.isEmpty {
                    HMSEmptyState(icon: "stethoscope", title: "No Care Team", message: "Your care team members will appear here.")
                } else {
                    careTeamList
                }
            }
            .navigationTitle("Care Team")
            .task { await viewModel.loadCareTeam() }
            .refreshable { await viewModel.loadCareTeam() }
        }
    }

    private var careTeamList: some View {
        List(viewModel.members) { member in
            NavigationLink {
                CareTeamMemberDetailView(member: member)
            } label: {
                careTeamRow(member)
            }
        }
        .listStyle(.plain)
    }

    private func careTeamRow(_ member: CareTeamMemberDto) -> some View {
        HStack(spacing: 12) {
            // Avatar
            ZStack {
                Circle()
                    .fill(Color.hmsPrimary.opacity(0.15))
                    .frame(width: 44, height: 44)
                Text(member.initials)
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsPrimary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(member.displayName)
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)
                if let specialty = member.specialty {
                    Text(specialty)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsAccent)
                }
                if let role = member.role {
                    Text(role)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.hmsTextTertiary)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Member Detail

struct CareTeamMemberDetailView: View {
    let member: CareTeamMemberDto

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Avatar
                ZStack {
                    Circle()
                        .fill(Color.hmsPrimary.opacity(0.15))
                        .frame(width: 80, height: 80)
                    Text(member.initials)
                        .font(.hmsTitle)
                        .foregroundColor(.hmsPrimary)
                }
                .padding(.top, 16)

                Text(member.displayName)
                    .font(.hmsHeadline)
                    .foregroundColor(.hmsTextPrimary)

                if let specialty = member.specialty {
                    Text(specialty)
                        .font(.hmsBody)
                        .foregroundColor(.hmsAccent)
                }

                // Info cards
                VStack(spacing: 12) {
                    if let role = member.role {
                        infoRow(icon: "person.badge.shield.checkmark", label: "Role", value: role)
                    }
                    if let dept = member.department {
                        infoRow(icon: "building.2", label: "Department", value: dept)
                    }
                    if let hospital = member.hospitalName {
                        infoRow(icon: "cross.fill", label: "Hospital", value: hospital)
                    }
                    if let phone = member.phone {
                        infoRow(icon: "phone.fill", label: "Phone", value: phone)
                    }
                    if let email = member.email {
                        infoRow(icon: "envelope.fill", label: "Email", value: email)
                    }
                }
                .padding(16)
            }
        }
        .navigationTitle("Provider Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func infoRow(icon: String, label: String, value: String) -> some View {
        HMSCard {
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
                Spacer()
            }
        }
    }
}
