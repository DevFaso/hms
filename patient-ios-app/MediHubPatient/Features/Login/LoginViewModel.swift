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

    /// Set when the password was right and a second factor is due; the view
    /// presents the challenge sheet from it.
    @Published var mfaChallenge: MfaChallenge?
    @Published var mfaCode: String = ""
    @Published var mfaError: String?
    @Published var isVerifyingMfa = false

    @Published var showForgotPassword = false
    @Published var showActivation = false

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
            default: biometricType = "biometrics_generic".localized
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
            localizedReason: "biometric_reason".localized
        ) { [weak self] success, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if success {
                    do {
                        let outcome = try await authManager.biometricLogin()
                        handle(outcome)
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
            errorMessage = "login_credentials_required".localized
            return
        }
        isLoading = true
        errorMessage = nil

        Task {
            do {
                let outcome = try await authManager.login(username: username, password: password)
                handle(outcome)
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func handle(_ outcome: AuthManager.LoginOutcome) {
        switch outcome {
        case .signedIn:
            mfaChallenge = nil
        case let .mfaRequired(challenge):
            mfaCode = ""
            mfaError = nil
            mfaChallenge = challenge
        }
    }

    // MARK: - MFA challenge

    /// A 6-digit TOTP or an 8-character backup code, as the backend accepts.
    nonisolated static func isPlausibleMfaCode(_ code: String) -> Bool {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        return (6 ... 8).contains(trimmed.count) && trimmed.allSatisfy { $0.isLetter || $0.isNumber }
    }

    func verifyMfa() {
        guard let challenge = mfaChallenge else { return }
        guard Self.isPlausibleMfaCode(mfaCode) else {
            mfaError = "mfa_invalid_code".localized
            return
        }
        isVerifyingMfa = true
        mfaError = nil
        Task {
            do {
                try await authManager.verifyMfa(challenge, code: mfaCode)
                mfaChallenge = nil
            } catch {
                mfaError = error.localizedDescription
            }
            isVerifyingMfa = false
        }
    }

    func cancelMfa() {
        authManager.cancelMfa()
        mfaChallenge = nil
        mfaCode = ""
        mfaError = nil
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
                // Resolves the HMS user id before the session opens; chat
                // and the history notes need it and the token cannot give it.
                try await authManager.completeSsoSession()
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
