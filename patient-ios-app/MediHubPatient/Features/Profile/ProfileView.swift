import SwiftUI

struct ProfileView: View {
    @StateObject private var vm = ProfileViewModel()
    @EnvironmentObject var authManager: AuthManager
    @State private var showLogoutConfirm = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading { ProgressView("Loading profile…") }
                else if let profile = vm.profile {
                    List {
                        // Avatar + Name
                        Section {
                            HStack(spacing: 16) {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.system(size: 60))
                                    .foregroundColor(.accentColor)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(profile.fullName).font(.title3).bold()
                                    if let email = profile.email { Text(email).font(.caption).foregroundColor(.secondary) }
                                    if let phone = profile.displayPhone {
                                        Text(phone).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                            .padding(.vertical, 8)
                        }

                        // Personal Info
                        Section("Personal Information") {
                            ProfileRow(label: "Date of Birth", value: profile.dateOfBirth)
                            ProfileRow(label: "Gender", value: profile.gender)
                            ProfileRow(label: "Blood Type", value: profile.bloodType)
                            ProfileRow(label: "Language", value: profile.preferredLanguage)
                        }

                        // Address
                        Section("Address") {
                            ProfileRow(label: "Street", value: profile.addressLine1)
                            ProfileRow(label: "City", value: profile.city)
                            ProfileRow(label: "State", value: profile.state)
                            ProfileRow(label: "ZIP", value: profile.zipCode)
                        }

                        // Insurance
                        Section("Insurance") {
                            ProfileRow(label: "Provider", value: profile.insuranceProvider)
                            ProfileRow(label: "Policy #", value: profile.insurancePolicyNumber)
                            ProfileRow(label: "Group #", value: profile.insuranceGroupNumber)
                            ProfileRow(label: "Plan Type", value: profile.insurancePlanType)
                            if let memberId = profile.insuranceMemberId {
                                ProfileRow(label: "Member ID", value: memberId)
                            }
                            if let plan = profile.insurancePlan {
                                ProfileRow(label: "Plan", value: plan)
                            }
                        }

                        // Allergies
                        if !profile.allergiesList.isEmpty {
                            Section("Allergies") {
                                ForEach(profile.allergiesList, id: \.self) { allergy in
                                    Label(allergy, systemImage: "exclamationmark.triangle.fill")
                                        .foregroundColor(.red)
                                }
                            }
                        }

                        // Emergency Contact
                        Section("Emergency Contact") {
                            ProfileRow(label: "Name", value: profile.emergencyContactName)
                            ProfileRow(label: "Phone", value: profile.emergencyContactPhone)
                            if let rel = profile.emergencyContactRelationship {
                                ProfileRow(label: "Relationship", value: rel)
                            }
                        }

                        // Actions
                        Section {
                            NavigationLink("Health Records") { HealthRecordsView() }
                            NavigationLink("Documents") { DocumentsView() }
                            NavigationLink("Sharing & Privacy") { SharingPrivacyView() }
                            NavigationLink("Family Access") { FamilyAccessView() }
                        }

                        // Logout
                        Section {
                            Button(role: .destructive) {
                                showLogoutConfirm = true
                            } label: {
                                Label("Log Out", systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                } else {
                    ContentUnavailableView("Profile Unavailable",
                        systemImage: "person.crop.circle.badge.exclamationmark",
                        description: Text("Could not load profile."))
                }
            }
            .navigationTitle("Profile")
            .refreshable { await vm.load() }
            .confirmationDialog("Log Out", isPresented: $showLogoutConfirm) {
                Button("Log Out", role: .destructive) { authManager.logout() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Are you sure you want to log out?") }
        }
        .task { await vm.load() }
    }
}

struct ProfileRow: View {
    let label: String
    let value: String?
    var body: some View {
        if let v = value, !v.isEmpty {
            HStack {
                Text(label).foregroundColor(.secondary)
                Spacer()
                Text(v).multilineTextAlignment(.trailing)
            }
        }
    }
}

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published var profile: PatientProfileDTO?
    @Published var isLoading = false

    func load() async {
        isLoading = true
        profile = try? await APIClient.shared.get(APIEndpoints.profile)
        isLoading = false
    }
}
