import SwiftUI
import PhotosUI

struct ProfileView: View {
    @StateObject private var vm = ProfileViewModel()
    @EnvironmentObject var authManager: AuthManager
    @State private var showLogoutConfirm = false
    @State private var isEditing = false
    @State private var showPhotoPicker = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showError = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.profile == nil { ProgressView("Loading profile…") }
                else if let profile = vm.profile {
                    List {
                        // Avatar + Name
                        Section {
                            HStack(spacing: 16) {
                                // Profile photo
                                ZStack(alignment: .bottomTrailing) {
                                    if let img = vm.profileImage {
                                        Image(uiImage: img)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(width: 72, height: 72)
                                            .clipShape(Circle())
                                    } else if let url = profile.profileImageUrl, !url.isEmpty {
                                        AsyncImage(url: URL(string: url.hasPrefix("http") ? url : AppEnvironment.baseURL.replacingOccurrences(of: "/api", with: "") + url)) { phase in
                                            if let img = phase.image {
                                                img.resizable().scaledToFill()
                                            } else {
                                                Image(systemName: "person.crop.circle.fill")
                                                    .font(.system(size: 60))
                                                    .foregroundColor(.accentColor)
                                            }
                                        }
                                        .frame(width: 72, height: 72)
                                        .clipShape(Circle())
                                    } else {
                                        Image(systemName: "person.crop.circle.fill")
                                            .font(.system(size: 60))
                                            .foregroundColor(.accentColor)
                                    }

                                    // Camera badge
                                    Button {
                                        showPhotoPicker = true
                                    } label: {
                                        Image(systemName: "camera.fill")
                                            .font(.caption2)
                                            .foregroundColor(.white)
                                            .padding(6)
                                            .background(Color.accentColor)
                                            .clipShape(Circle())
                                    }
                                    .offset(x: 4, y: 4)
                                }

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

                        if isEditing {
                            editableContent
                        } else {
                            readOnlyContent(profile)
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
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if vm.profile != nil {
                        Button(isEditing ? "Save" : "Edit") {
                            if isEditing {
                                Task {
                                    await vm.saveProfile()
                                    isEditing = false
                                }
                            } else {
                                vm.prepareEdit()
                                isEditing = true
                            }
                        }
                        .bold(isEditing)
                    }
                }
                if isEditing {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("Cancel") {
                            isEditing = false
                        }
                    }
                }
            }
            .refreshable { await vm.load() }
            .confirmationDialog("Log Out", isPresented: $showLogoutConfirm) {
                Button("Log Out", role: .destructive) { authManager.logout() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Are you sure you want to log out?") }
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhoto, matching: .images)
            .onChange(of: selectedPhoto) { _, newItem in
                if let newItem {
                    Task {
                        if let data = try? await newItem.loadTransferable(type: Data.self) {
                            await vm.uploadPhoto(data: data)
                        }
                    }
                }
            }
            .onChange(of: vm.errorMessage) { _, newVal in showError = (newVal != nil) }
            .alert("Error", isPresented: $showError) {
                Button("OK") { vm.errorMessage = nil }
            } message: {
                Text(vm.errorMessage ?? "")
            }
        }
        .task { await vm.load() }
    }

    // MARK: - Read-only sections

    @ViewBuilder
    private func readOnlyContent(_ profile: PatientProfileDTO) -> some View {
        Section("Personal Information") {
            ProfileRow(label: "Date of Birth", value: profile.dateOfBirth)
            ProfileRow(label: "Gender", value: profile.gender)
            ProfileRow(label: "Blood Type", value: profile.bloodType)
            ProfileRow(label: "Language", value: profile.preferredLanguage)
            ProfileRow(label: "Username", value: profile.username)
        }

        Section("Address") {
            if let addr = profile.displayAddress {
                Text(addr).font(.subheadline)
            } else {
                Text("No address on file").foregroundColor(.secondary)
            }
        }

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

        Section("Allergies") {
            if profile.allergiesList.isEmpty {
                Text("No known allergies").foregroundColor(.secondary)
            } else {
                ForEach(profile.allergiesList, id: \.self) { allergy in
                    Label(allergy, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(.red)
                }
            }
        }

        Section("Emergency Contact") {
            ProfileRow(label: "Name", value: profile.emergencyContactName)
            ProfileRow(label: "Phone", value: profile.emergencyContactPhone)
            if let rel = profile.emergencyContactRelationship {
                ProfileRow(label: "Relationship", value: rel)
            }
        }
    }

    // MARK: - Editable sections

    @ViewBuilder
    private var editableContent: some View {
        Section("Contact Information") {
            EditableRow(label: "Phone", text: $vm.editPhone)
            EditableRow(label: "Secondary Phone", text: $vm.editPhoneSecondary)
            EditableRow(label: "Email", text: $vm.editEmail)
        }

        Section("Address") {
            EditableRow(label: "Address Line 1", text: $vm.editAddress1)
            EditableRow(label: "Address Line 2", text: $vm.editAddress2)
            EditableRow(label: "City", text: $vm.editCity)
            EditableRow(label: "State", text: $vm.editState)
            EditableRow(label: "ZIP Code", text: $vm.editZip)
            EditableRow(label: "Country", text: $vm.editCountry)
        }

        Section("Emergency Contact") {
            EditableRow(label: "Name", text: $vm.editEmergencyName)
            EditableRow(label: "Phone", text: $vm.editEmergencyPhone)
            EditableRow(label: "Relationship", text: $vm.editEmergencyRelationship)
        }

        Section("Pharmacy") {
            EditableRow(label: "Preferred Pharmacy", text: $vm.editPharmacy)
        }

        if vm.isSaving {
            Section {
                HStack { Spacer(); ProgressView("Saving…"); Spacer() }
            }
        }
    }
}

struct EditableRow: View {
    let label: String
    @Binding var text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundColor(.secondary)
            TextField(label, text: $text)
        }
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
    @Published var profileImage: UIImage?
    @Published var isLoading = false
    @Published var isSaving = false
    @Published var errorMessage: String?

    // Edit fields
    @Published var editPhone = ""
    @Published var editPhoneSecondary = ""
    @Published var editEmail = ""
    @Published var editAddress1 = ""
    @Published var editAddress2 = ""
    @Published var editCity = ""
    @Published var editState = ""
    @Published var editZip = ""
    @Published var editCountry = ""
    @Published var editEmergencyName = ""
    @Published var editEmergencyPhone = ""
    @Published var editEmergencyRelationship = ""
    @Published var editPharmacy = ""

    func load() async {
        isLoading = true
        profile = try? await APIClient.shared.get(APIEndpoints.profile)
        ProfileImageManager.shared.update(url: profile?.profileImageUrl)
        isLoading = false
    }

    func prepareEdit() {
        guard let p = profile else { return }
        editPhone = p.displayPhone ?? ""
        editPhoneSecondary = p.phoneNumberSecondary ?? ""
        editEmail = p.email ?? ""
        editAddress1 = p.addressLine1 ?? ""
        editAddress2 = p.addressLine2 ?? ""
        editCity = p.city ?? ""
        editState = p.state ?? ""
        editZip = p.zipCode ?? ""
        editCountry = p.country ?? ""
        editEmergencyName = p.emergencyContactName ?? ""
        editEmergencyPhone = p.emergencyContactPhone ?? ""
        editEmergencyRelationship = p.emergencyContactRelationship ?? ""
        editPharmacy = p.preferredPharmacy ?? ""
    }

    func saveProfile() async {
        isSaving = true
        let dto = PatientProfileUpdateDTO(
            phoneNumberPrimary: editPhone.isEmpty ? nil : editPhone,
            phoneNumberSecondary: editPhoneSecondary.isEmpty ? nil : editPhoneSecondary,
            email: editEmail.isEmpty ? nil : editEmail,
            addressLine1: editAddress1.isEmpty ? nil : editAddress1,
            addressLine2: editAddress2.isEmpty ? nil : editAddress2,
            city: editCity.isEmpty ? nil : editCity,
            state: editState.isEmpty ? nil : editState,
            zipCode: editZip.isEmpty ? nil : editZip,
            country: editCountry.isEmpty ? nil : editCountry,
            emergencyContactName: editEmergencyName.isEmpty ? nil : editEmergencyName,
            emergencyContactPhone: editEmergencyPhone.isEmpty ? nil : editEmergencyPhone,
            emergencyContactRelationship: editEmergencyRelationship.isEmpty ? nil : editEmergencyRelationship,
            preferredPharmacy: editPharmacy.isEmpty ? nil : editPharmacy
        )
        do {
            let updated: PatientProfileDTO = try await APIClient.shared.put(APIEndpoints.updateProfile, body: dto)
            profile = updated
        } catch {
            errorMessage = error.localizedDescription
        }
        isSaving = false
    }

    func uploadPhoto(data: Data) async {
        // Convert to JPEG
        guard let image = UIImage(data: data),
              let jpegData = image.jpegData(compressionQuality: 0.8) else {
            errorMessage = "Could not process the selected image."
            return
        }
        profileImage = image
        do {
            let response: ProfileImageResponse = try await APIClient.shared.uploadMultipart(
                APIEndpoints.uploadProfileImage,
                fileData: jpegData,
                fileName: "profile.jpg",
                mimeType: "image/jpeg"
            )
            // Reload profile to get updated URL
            await load()
            // Immediately broadcast so tab bar updates
            ProfileImageManager.shared.update(url: profile?.profileImageUrl)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
