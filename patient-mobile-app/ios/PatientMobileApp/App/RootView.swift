import SwiftUI

/// Root view that switches between auth flow, biometric lock, and main tab navigation
struct RootView: View {
    @EnvironmentObject var authManager: AuthManager

    var body: some View {
        Group {
            if authManager.requiresBiometric {
                BiometricLockView()
                    .transition(.opacity)
            } else if authManager.isAuthenticated {
                MainTabView()
                    .transition(.opacity)
            } else {
                LoginView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: authManager.isAuthenticated)
        .animation(.easeInOut(duration: 0.3), value: authManager.requiresBiometric)
    }
}

// MARK: - Biometric Lock Screen

private struct BiometricLockView: View {
    @EnvironmentObject var authManager: AuthManager
    private let biometric = BiometricService.shared

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: biometric.biometryTypeName == "Face ID" ? "faceid" : "touchid")
                .font(.system(size: 64))
                .foregroundColor(.hmsPrimary)

            Text("Locked")
                .font(.hmsTitle)
                .foregroundColor(.hmsTextPrimary)

            Text("Use \(biometric.biometryTypeName) to unlock")
                .font(.hmsBody)
                .foregroundColor(.hmsTextSecondary)

            HMSPrimaryButton("Unlock with \(biometric.biometryTypeName)") {
                Task { await authManager.authenticateWithBiometric() }
            }
            .padding(.horizontal, 32)

            Button("Use Password Instead") {
                authManager.skipBiometric()
            }
            .font(.hmsCaption)
            .foregroundColor(.hmsTextTertiary)

            Spacer()
        }
        .task {
            await authManager.authenticateWithBiometric()
        }
    }
}
