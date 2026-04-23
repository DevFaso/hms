import Combine
import Foundation

// MARK: - AuthManager

// Single source of truth for authentication state.
// Published as @EnvironmentObject throughout the app.

@MainActor
final class AuthManager: ObservableObject {
    static let shared = AuthManager()
    private init() {
        restoreSession()
    }

    // MARK: - Published state

    @Published var isAuthenticated: Bool = false
    @Published var currentUser: UserDTO?

    // MARK: - Session restore

    private func restoreSession() {
        isAuthenticated = KeychainHelper.shared.accessToken != nil
            || KeychainHelper.shared.oidcAccessToken != nil
    }

    // MARK: - Login

    func login(username: String, password: String) async throws {
        let request = LoginRequest(username: username, password: password)
        let response: LoginResponse = try await APIClient.shared.post(
            APIEndpoints.login,
            body: request,
            requiresAuth: false
        )

        // ── Patient-only gate ──────────────────────────────────
        // The mobile app is exclusively for patients.  Reject any user
        // who does not hold ROLE_PATIENT.
        let roles = (response.roles ?? []).map { $0.uppercased() }
        guard roles.contains(where: { $0.contains("PATIENT") }) else {
            throw AuthError.notPatient
        }

        KeychainHelper.shared.accessToken = response.accessToken ?? response.token
        KeychainHelper.shared.refreshToken = response.refreshToken
        KeychainHelper.shared.savedUsername = username
        KeychainHelper.shared.savedPassword = password
        currentUser = response.user
        isAuthenticated = true
    }

    // MARK: - Biometric login

    // Retrieves stored credentials from Keychain and re-authenticates.

    func biometricLogin() async throws {
        guard let username = KeychainHelper.shared.savedUsername,
              let password = KeychainHelper.shared.savedPassword
        else {
            throw AuthError.noSavedCredentials
        }
        try await login(username: username, password: password)
    }

    // MARK: - Logout

    func logout() {
        Task {
            try? await APIClient.shared.post(APIEndpoints.logout, body: EmptyBody()) as EmptyResponse
        }
        KeychainHelper.shared.accessToken = nil
        KeychainHelper.shared.refreshToken = nil
        KeychainHelper.shared.clearOidc()
        KeycloakAuthService.shared.clear()
        currentUser = nil
        isAuthenticated = false
    }

    // MARK: - SSO (KC-3)

    /// Marks the session authenticated after a successful Keycloak login.
    /// Called by `LoginViewModel` once `KeycloakAuthService.login(...)` resolves.
    func completeSsoSession() {
        isAuthenticated = KeychainHelper.shared.oidcAccessToken != nil
    }

    // MARK: - Token refresh

    // Called automatically by APIClient on 401.

    func refreshTokens() async throws {
        guard let refreshToken = KeychainHelper.shared.refreshToken else {
            logout()
            throw APIError.unauthorized
        }
        let body = RefreshTokenRequest(refreshToken: refreshToken)
        let response: LoginResponse = try await APIClient.shared.post(
            APIEndpoints.tokenRefresh,
            body: body,
            requiresAuth: false
        )
        KeychainHelper.shared.accessToken = response.accessToken ?? response.token
        if let newRefresh = response.refreshToken {
            KeychainHelper.shared.refreshToken = newRefresh
        }
    }
}

// MARK: - Auth Errors

enum AuthError: LocalizedError {
    case noSavedCredentials
    case biometricFailed
    case notPatient

    var errorDescription: String? {
        switch self {
        case .noSavedCredentials: "No saved credentials. Please log in with username first."
        case .biometricFailed: "Biometric authentication failed."
        case .notPatient: "This app is for patients only. Please use the web portal to sign in."
        }
    }
}

// MARK: - Request/Response Models (Auth-specific)

struct LoginRequest: Encodable {
    let username: String
    let password: String
}

struct RefreshTokenRequest: Encodable {
    let refreshToken: String
}

struct LoginResponse: Decodable {
    // Token fields
    let accessToken: String?
    let token: String? // fallback field name some backends use
    let refreshToken: String?

    // User fields (flat — not nested under "user")
    let id: String?
    let username: String?
    let email: String?
    let firstName: String?
    let lastName: String?
    let roles: [String]?
    let roleName: String?
    let patientId: String?
    let active: Bool?
    let forcePasswordChange: Bool?

    /// Build a UserDTO from the flat response fields
    var user: UserDTO {
        UserDTO(id: id, username: username, email: email,
                firstName: firstName, lastName: lastName,
                role: roleName ?? roles?.first,
                organizationId: nil, hospitalId: nil)
    }
}

struct EmptyBody: Encodable {}
struct EmptyResponse: Decodable {}
