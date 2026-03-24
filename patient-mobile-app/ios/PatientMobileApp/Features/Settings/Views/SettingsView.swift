import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var authManager: AuthManager
    @State private var showLogoutConfirm = false
    @AppStorage("biometricEnabled") private var biometricEnabled = false

    private let biometric = BiometricService.shared

    var body: some View {
        List {
            Section("Account") {
                NavigationLink(destination: ProfileView()) {
                    SettingsRow(icon: "person.circle", title: "Edit Profile")
                }
            }

            Section("Security") {
                NavigationLink {
                    HMSEmptyState(icon: "lock.shield", title: "Change Password", message: "Coming soon")
                } label: {
                    SettingsRow(icon: "lock", title: "Change Password")
                }

                if biometric.isAvailable {
                    Toggle(isOn: $biometricEnabled) {
                        SettingsRow(icon: "faceid", title: "\(biometric.biometryTypeName) Login")
                    }
                    .onChange(of: biometricEnabled) { _, enabled in
                        if enabled {
                            Task {
                                do {
                                    let ok = try await biometric.authenticate(
                                        reason: "Enable \(biometric.biometryTypeName) for quick login"
                                    )
                                    if !ok { biometricEnabled = false }
                                } catch {
                                    biometricEnabled = false
                                }
                            }
                        }
                    }
                }
            }

            Section("Preferences") {
                NavigationLink {
                    HMSEmptyState(icon: "bell", title: "Notifications", message: "Manage notification preferences")
                } label: {
                    SettingsRow(icon: "bell.badge", title: "Notifications")
                }

                NavigationLink {
                    HMSEmptyState(icon: "globe", title: "Language", message: "Multilingual support coming soon")
                } label: {
                    SettingsRow(icon: "globe", title: "Language")
                }
            }

            Section("About") {
                HStack {
                    SettingsRow(icon: "info.circle", title: "Version")
                    Spacer()
                    Text(appVersion)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }

                NavigationLink {
                    HMSEmptyState(icon: "doc.text", title: "Terms of Service", message: "")
                } label: {
                    SettingsRow(icon: "doc.text", title: "Terms of Service")
                }

                NavigationLink {
                    HMSEmptyState(icon: "hand.raised", title: "Privacy Policy", message: "")
                } label: {
                    SettingsRow(icon: "hand.raised", title: "Privacy Policy")
                }
            }

            Section {
                Button(role: .destructive) {
                    showLogoutConfirm = true
                } label: {
                    HStack {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                        Text("Sign Out")
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle("Settings")
        .alert("Sign Out", isPresented: $showLogoutConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Sign Out", role: .destructive) {
                Task { await authManager.logout() }
            }
        } message: {
            Text("Are you sure you want to sign out?")
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

private struct SettingsRow: View {
    let icon: String
    let title: String

    var body: some View {
        Label {
            Text(title)
                .font(.hmsBody)
        } icon: {
            Image(systemName: icon)
                .foregroundColor(.hmsPrimary)
        }
    }
}
