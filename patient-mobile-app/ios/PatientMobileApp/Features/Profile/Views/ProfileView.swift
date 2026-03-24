import SwiftUI

struct ProfileView: View {
    @StateObject private var viewModel = ProfileViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                if viewModel.isLoading {
                    HMSLoadingView()
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) {
                        Task { await viewModel.load() }
                    }
                } else if let profile = viewModel.profile {
                    VStack(spacing: 20) {
                        // Avatar / Name Header
                        HMSCard {
                            VStack(spacing: 12) {
                                Image(systemName: "person.circle.fill")
                                    .font(.system(size: 72))
                                    .foregroundColor(.hmsPrimary)

                                Text(profile.fullName)
                                    .font(.hmsHeadline)

                                if let mrn = profile.mrn {
                                    Text("MRN: \(mrn)")
                                        .font(.hmsCaption)
                                        .foregroundColor(.hmsTextSecondary)
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }

                        // Personal Info
                        HMSSectionHeader(title: "Personal Information")
                        HMSCard {
                            VStack(spacing: 12) {
                                ProfileRow(label: "Date of Birth", value: profile.dateOfBirth ?? "—")
                                Divider()
                                ProfileRow(label: "Gender", value: profile.gender ?? "—")
                                Divider()
                                ProfileRow(label: "Blood Type", value: profile.bloodType ?? "—")
                            }
                        }

                        // Contact Info
                        HMSSectionHeader(title: "Contact Information")
                        HMSCard {
                            VStack(spacing: 12) {
                                ProfileRow(label: "Email", value: profile.email ?? "—")
                                Divider()
                                ProfileRow(label: "Phone", value: profile.phone ?? "—")
                                Divider()
                                ProfileRow(label: "Address", value: profile.address ?? "—")
                            }
                        }

                        // Emergency Contact
                        HMSSectionHeader(title: "Emergency Contact")
                        HMSCard {
                            VStack(spacing: 12) {
                                ProfileRow(label: "Name", value: profile.emergencyContactName ?? "—")
                                Divider()
                                ProfileRow(label: "Phone", value: profile.emergencyContactPhone ?? "—")
                                Divider()
                                ProfileRow(label: "Relationship", value: profile.emergencyContactRelation ?? "—")
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .background(Color.hmsBackground)
            .navigationTitle("Profile")
            .task {
                await viewModel.load()
            }
        }
    }
}

private struct ProfileRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.hmsCaption)
                .foregroundColor(.hmsTextSecondary)
            Spacer()
            Text(value)
                .font(.hmsBodyMedium)
                .foregroundColor(.hmsTextPrimary)
        }
    }
}
