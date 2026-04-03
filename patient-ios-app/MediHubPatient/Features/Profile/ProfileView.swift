import PhotosUI
import SwiftUI

struct ProfileView: View {
    @StateObject private var vm = ProfileViewModel()
    @EnvironmentObject var authManager: AuthManager
    @EnvironmentObject var localization: LocalizationManager
    @State private var showLogoutConfirm = false
    @State private var isEditing = false
    @State private var showPhotoPicker = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var showError = false
    @State private var showLanguagePicker = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading, vm.profile == nil {
                    VStack(spacing: 12) {
                        ProgressView().controlSize(.large)
                        Text("loading".localized)
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                } else if let profile = vm.profile {
                    List {
                        // Avatar + Name header
                        Section {
                            HStack(spacing: 16) {
                                ZStack(alignment: .bottomTrailing) {
                                    if let img = vm.profileImage {
                                        Image(uiImage: img)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(width: 76, height: 76)
                                            .clipShape(Circle())
                                            .overlay(Circle().stroke(Color("BrandBlue").opacity(0.2), lineWidth: 2))
                                    } else if let url = profile.profileImageUrl, !url.isEmpty {
                                        AsyncImage(url: URL(string: url.hasPrefix("http") ? url : AppEnvironment.baseURL.replacingOccurrences(of: "/api", with: "") + url)) { phase in
                                            if let img = phase.image {
                                                img.resizable().scaledToFill()
                                            } else {
                                                Image(systemName: "person.crop.circle.fill")
                                                    .font(.system(size: 64))
                                                    .foregroundStyle(Color("BrandBlue").opacity(0.6))
                                            }
                                        }
                                        .frame(width: 76, height: 76)
                                        .clipShape(Circle())
                                        .overlay(Circle().stroke(Color("BrandBlue").opacity(0.2), lineWidth: 2))
                                    } else {
                                        Image(systemName: "person.crop.circle.fill")
                                            .font(.system(size: 64))
                                            .foregroundStyle(Color("BrandBlue").opacity(0.6))
                                    }

                                    Button { showPhotoPicker = true } label: {
                                        Image(systemName: "camera.fill")
                                            .font(.system(size: 10, weight: .semibold))
                                            .foregroundStyle(.white)
                                            .padding(7)
                                            .background(Color("BrandBlue"))
                                            .clipShape(Circle())
                                            .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
                                    }
                                    .offset(x: 4, y: 4)
                                }

                                VStack(alignment: .leading, spacing: 5) {
                                    Text(profile.fullName)
                                        .font(.title3.weight(.bold))
                                    if let email = profile.email {
                                        Text(email)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    if let phone = profile.displayPhone {
                                        Text(phone)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .padding(.vertical, 10)
                        }

                        if isEditing {
                            editableContent
                        } else {
                            readOnlyContent(profile)
                        }

                        // Language
                        Section {
                            Button {
                                showLanguagePicker = true
                            } label: {
                                HStack {
                                    Label("language".localized, systemImage: "globe")
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    Text(LocalizationManager.supportedLanguages.first { $0.code == localization.currentLanguage }?.name ?? localization.currentLanguage)
                                        .foregroundStyle(.secondary)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(.tertiary)
                                }
                            }
                        }

                        // Actions
                        Section {
                            NavigationLink { HealthRecordsView() } label: {
                                Label("health_records".localized, systemImage: "heart.text.square")
                            }
                            NavigationLink { DocumentsView() } label: {
                                Label("documents".localized, systemImage: "doc.fill")
                            }
                            NavigationLink { SharingPrivacyView() } label: {
                                Label("sharing_privacy".localized, systemImage: "lock.shield")
                            }
                            NavigationLink { FamilyAccessView() } label: {
                                Label("family_access".localized, systemImage: "person.2.circle")
                            }
                        }

                        // Logout
                        Section {
                            Button(role: .destructive) {
                                showLogoutConfirm = true
                            } label: {
                                Label("logout".localized, systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                } else {
                    ContentUnavailableView("profile_unavailable".localized,
                                           systemImage: "person.crop.circle.badge.exclamationmark",
                                           description: Text("profile_load_error".localized))
                }
            }
            .navigationTitle("tab_profile".localized)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if vm.profile != nil {
                        Button(isEditing ? "save".localized : "edit".localized) {
                            if isEditing {
                                Task { await vm.saveProfile(); isEditing = false }
                            } else {
                                vm.prepareEdit(); isEditing = true
                            }
                        }
                        .fontWeight(isEditing ? .bold : .regular)
                    }
                }
                if isEditing {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("cancel".localized) { isEditing = false }
                    }
                }
            }
            .refreshable { await vm.load() }
            .confirmationDialog("logout".localized, isPresented: $showLogoutConfirm) {
                Button("logout".localized, role: .destructive) { authManager.logout() }
                Button("cancel".localized, role: .cancel) {}
            } message: { Text("logout_confirm".localized) }
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
            .alert("error".localized, isPresented: $showError) {
                Button("ok".localized) { vm.errorMessage = nil }
            } message: {
                Text(vm.errorMessage ?? "")
            }
            .sheet(isPresented: $showLanguagePicker) {
                LanguagePickerSheet()
            }
        }
        .task { await vm.load() }
    }

    // MARK: - Read-only sections

    @ViewBuilder
    private func readOnlyContent(_ profile: PatientProfileDTO) -> some View {
        Section("personal_information".localized) {
            ProfileRow(label: "date_of_birth".localized, value: profile.dateOfBirth)
            ProfileRow(label: "gender".localized, value: profile.gender)
            ProfileRow(label: "blood_type".localized, value: profile.bloodType)
            ProfileRow(label: "language".localized, value: profile.preferredLanguage)
            ProfileRow(label: "username".localized, value: profile.username)
        }

        Section("address".localized) {
            if let addr = profile.displayAddress {
                Text(addr).font(.subheadline)
            } else {
                Text("no_address".localized).foregroundStyle(.secondary)
            }
        }

        Section("insurance".localized) {
            ProfileRow(label: "provider".localized, value: profile.insuranceProvider)
            ProfileRow(label: "policy_number".localized, value: profile.insurancePolicyNumber)
            ProfileRow(label: "group_number".localized, value: profile.insuranceGroupNumber)
            ProfileRow(label: "plan_type".localized, value: profile.insurancePlanType)
            if let memberId = profile.insuranceMemberId {
                ProfileRow(label: "member_id".localized, value: memberId)
            }
            if let plan = profile.insurancePlan {
                ProfileRow(label: "plan".localized, value: plan)
            }
        }

        Section("allergies".localized) {
            if profile.allergiesList.isEmpty {
                Text("no_allergies".localized).foregroundStyle(.secondary)
            } else {
                ForEach(profile.allergiesList, id: \.self) { allergy in
                    Label(allergy, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
        }

        Section("emergency_contact".localized) {
            ProfileRow(label: "name".localized, value: profile.emergencyContactName)
            ProfileRow(label: "phone".localized, value: profile.emergencyContactPhone)
            if let rel = profile.emergencyContactRelationship {
                ProfileRow(label: "relationship".localized, value: rel)
            }
        }
    }

    // MARK: - Editable sections

    @ViewBuilder
    private var editableContent: some View {
        Section("contact_info".localized) {
            EditableRow(label: "phone".localized, text: $vm.editPhone)
            EditableRow(label: "secondary_phone".localized, text: $vm.editPhoneSecondary)
            EditableRow(label: "email".localized, text: $vm.editEmail)
        }

        Section("address".localized) {
            EditableRow(label: "address_line_1".localized, text: $vm.editAddress1)
            EditableRow(label: "address_line_2".localized, text: $vm.editAddress2)
            EditableRow(label: "city".localized, text: $vm.editCity)
            EditableRow(label: "state".localized, text: $vm.editState)
            EditableRow(label: "zip_code".localized, text: $vm.editZip)
            EditableRow(label: "country".localized, text: $vm.editCountry)
        }

        Section("emergency_contact".localized) {
            EditableRow(label: "name".localized, text: $vm.editEmergencyName)
            EditableRow(label: "phone".localized, text: $vm.editEmergencyPhone)
            EditableRow(label: "relationship".localized, text: $vm.editEmergencyRelationship)
        }

        Section("pharmacy".localized) {
            EditableRow(label: "preferred_pharmacy".localized, text: $vm.editPharmacy)
        }

        if vm.isSaving {
            Section {
                HStack {
                    Spacer()
                    ProgressView()
                    Text("saving".localized).font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                }
            }
        }
    }
}

// MARK: - Language Picker

struct LanguagePickerSheet: View {
    @EnvironmentObject var localization: LocalizationManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(LocalizationManager.supportedLanguages, id: \.code) { lang in
                    Button {
                        localization.setLanguage(lang.code)
                        dismiss()
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(lang.name)
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                Text(lang.code.uppercased())
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if localization.currentLanguage == lang.code {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(Color("BrandBlue"))
                                    .font(.title3)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("select_language".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("done".localized) { dismiss() }
                }
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
              let jpegData = image.jpegData(compressionQuality: 0.8)
        else {
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
