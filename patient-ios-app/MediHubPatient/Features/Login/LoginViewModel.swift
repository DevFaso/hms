import Foundation
import LocalAuthentication

@MainActor
final class LoginViewModel: ObservableObject {
    @Published var username: String = ""
    @Published var password: String = ""
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var showUsernameForm: Bool = false
    @Published var biometricAvailable: Bool = false
    @Published var biometricType: String = "Biometrics"

    private let authManager = AuthManager.shared

    init() {
        checkBiometricAvailability()
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
}
