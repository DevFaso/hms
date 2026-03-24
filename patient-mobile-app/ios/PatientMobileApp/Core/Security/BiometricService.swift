import Foundation
import LocalAuthentication

/// Face ID / Touch ID wrapper
final class BiometricService {
    static let shared = BiometricService()
    private init() {}

    /// Whether the device supports biometrics
    var isAvailable: Bool {
        let context = LAContext()
        var error: NSError?
        return context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Human-readable type: "Face ID", "Touch ID", or "Optic ID"
    var biometryTypeName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID:   return "Face ID"
        case .touchID:  return "Touch ID"
        case .opticID:  return "Optic ID"
        @unknown default: return "Biometrics"
        }
    }

    /// Prompt the user for biometric authentication
    func authenticate(reason: String = "Authenticate to access your health records") async throws -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = "Use Password"
        return try await context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        )
    }
}
