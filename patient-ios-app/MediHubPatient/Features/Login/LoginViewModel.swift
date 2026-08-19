import Foundation
import LocalAuthentication
import UIKit

@MainActor
final class LoginViewModel: ObservableObject {
    @Published var username: String = ""
    @Published var password: String = ""
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var showUsernameForm: Bool = false
    @Published var biometricAvailable: Bool = false
    @Published var biometricType: String = "Biometrics"
    /// KC-3 — reflects `FeatureFlags.keycloakSsoEnabled` at init time. The
    /// SSO button stays hidden until both the flag is ON and the build has a
    /// non-empty issuer configured.
    @Published var ssoEnabled: Bool = false

    private let authManager = AuthManager.shared

    init() {
        checkBiometricAvailability()
        ssoEnabled = FeatureFlags.keycloakSsoEnabled
    }

    // MARK: - Biometric check

    private func checkBiometricAvailability() {
        let context = LAContext()
        var error: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        biometricAvailable = available && KeychainHelper.shared.savedUsername != nil

        if available {
            switch context.biometryType {
            case .faceID: biometricType = "Face ID"
            case .touchID: biometricType = "Touch ID"
            case .opticID: biometricType = "Optic ID"
            default: biometricType = "Biometrics"
            }
        }
    }

    // MARK: - Biometric login

    func loginWithBiometrics() {
        let context = LAContext()
        isLoading = true
        errorMessage = nil

        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: "Log in to MediHub Patient"
        ) { [weak self] success, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if success {
                    do {
                        try await authManager.biometricLogin()
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                } else {
                    // User cancelled — don't show error
                    if let laError = error as? LAError, laError.code == .userCancel {
                        // silently ignore
                    } else {
                        errorMessage = error?.localizedDescription
                    }
                }
                isLoading = false
            }
        }
    }

    // MARK: - Username / Password login

    func loginWithCredentials() {
        guard !username.trimmingCharacters(in: .whitespaces).isEmpty,
              !password.isEmpty
        else {
            errorMessage = "Please enter your username and password."
            return
        }
        isLoading = true
        errorMessage = nil

        Task {
            do {
                try await authManager.login(username: username, password: password)
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    // MARK: - SSO (KC-3)

    /// Launches the Keycloak Authorization Code + PKCE flow from the given
    /// presenter. The caller is responsible for resolving the top
    /// `UIViewController` (e.g. via a SwiftUI `UIViewControllerRepresentable`
    /// or the key-window root VC).
    func loginWithSSO(presenter: UIViewController) {
        isLoading = true
        errorMessage = nil
        Task {
            do {
                try await KeycloakAuthService.shared.login(presenting: presenter)
                authManager.completeSsoSession()
            } catch {
                // Ignore user-cancel — AppAuth returns domain == OIDOAuthTokenError etc.
                let ns = error as NSError
                let userCancelled = ns.code == -3 // OIDErrorCode.userCanceledAuthorizationFlow
                if !userCancelled {
                    errorMessage = error.localizedDescription
                }
            }
            isLoading = false
        }
    }
}
