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
        guard isAuthenticated else { return }
        // A session restored from a build that predates the bootstrap call
        // (an SSO session especially) has no persisted user id; resolve it
        // once instead of leaving chat reporting "not signed in".
        Task { [weak self] in
            guard let self else { return }
            if KeychainHelper.shared.savedUserId == nil {
                _ = try? await self.resolveSessionIdentity()
            }
            PushManager.shared.sessionDidStart()
        }
    }

    /// The signed-in user's HMS id (`users.id`), surviving a relaunch.
    ///
    /// Both login paths persist it from `/auth/session/bootstrap`, so an SSO
    /// session — whose token `sub` is the Keycloak id, not this one — has it
    /// too. Chat and the device-only history notes are keyed by it.
    var currentUserId: String? {
        currentUser?.id ?? KeychainHelper.shared.savedUserId
    }

    /// `currentUserId`, resolving it from the server when a signed-in session
    /// somehow has none. Nil only when signed out or the server is unreachable.
    func ensureUserId() async -> String? {
        if let id = currentUserId, !id.isEmpty { return id }
        guard isAuthenticated else { return nil }
        return try? await resolveSessionIdentity()
    }

    // MARK: - Login

    enum LoginOutcome: Equatable {
        case signedIn
        /// The password was right and a second factor is due; finish with
        /// `verifyMfa(_:code:)`.
        case mfaRequired(MfaChallenge)
    }

    /// Kept only between the password step and the MFA step, so a successful
    /// challenge can still save them for Face ID the way a plain login does.
    private var pendingCredentials: (username: String, password: String)?

    func login(username: String, password: String) async throws -> LoginOutcome {
        pendingCredentials = nil
        var response: LoginResponse = try await APIClient.shared.post(
            APIEndpoints.login,
            body: LoginRequest(username: username, password: password, selectedRole: nil),
            requiresAuth: false
        )

        // A patient who also holds a staff role is asked to pick one first;
        // the app only ever signs in as the patient, so pick it for them.
        if response.roleSelectionRequired == true {
            guard let patientRole = (response.availableRoles ?? [])
                .first(where: { $0.uppercased().contains("PATIENT") }) else {
                throw AuthError.notPatient
            }
            response = try await APIClient.shared.post(
                APIEndpoints.login,
                body: LoginRequest(username: username, password: password, selectedRole: patientRole),
                requiresAuth: false
            )
        }

        // Before the patient gate: an MFA challenge carries no roles, so the
        // gate below would refuse it as "not a patient".
        if response.mfaRequired == true {
            guard let token = response.mfaToken, !token.isEmpty else { throw APIError.unknown }
            pendingCredentials = (username, password)
            return .mfaRequired(MfaChallenge(mfaToken: token,
                                             enrolled: response.mfaEnrolled ?? false,
                                             username: response.username ?? username))
        }

        try await completePasswordSignIn(response, username: username, password: password)
        return .signedIn
    }

    /// Second step of a login that answered `mfaRequired`: a TOTP or backup
    /// code. The success body is a normal login response.
    func verifyMfa(_ challenge: MfaChallenge, code: String) async throws {
        let response: LoginResponse = try await APIClient.shared.post(
            APIEndpoints.mfaVerify,
            body: MfaVerifyRequest(mfaToken: challenge.mfaToken,
                                   code: code.trimmingCharacters(in: .whitespacesAndNewlines)),
            requiresAuth: false
        )
        let credentials = pendingCredentials
        try await completePasswordSignIn(response,
                                         username: credentials?.username,
                                         password: credentials?.password)
    }

    func cancelMfa() {
        pendingCredentials = nil
    }

    private func completePasswordSignIn(_ response: LoginResponse, username: String?, password: String?) async throws {
        // ── Patient-only gate ──────────────────────────────────
        // The mobile app is exclusively for patients.  Reject any user
        // who does not hold ROLE_PATIENT.
        let roles = (response.roles ?? []).map { $0.uppercased() }
        guard roles.contains(where: { $0.contains("PATIENT") }) else {
            pendingCredentials = nil
            throw AuthError.notPatient
        }
        guard let token = response.accessToken ?? response.token, !token.isEmpty else {
            throw APIError.unknown
        }

        KeychainHelper.shared.accessToken = token
        KeychainHelper.shared.refreshToken = response.refreshToken
        if let username, let password {
            KeychainHelper.shared.savedUsername = username
            KeychainHelper.shared.savedPassword = password
        }
        pendingCredentials = nil
        currentUser = response.user
        KeychainHelper.shared.savedUserId = response.id
        // The login body already names the user; the bootstrap is the same
        // answer both login paths get, so it is asked here too. A failure is
        // not fatal on this path — the id above stands.
        _ = try? await resolveSessionIdentity()
        isAuthenticated = true
        PushManager.shared.sessionDidStart()
    }

    // MARK: - Biometric login

    // Retrieves stored credentials from Keychain and re-authenticates.

    func biometricLogin() async throws -> LoginOutcome {
        guard let username = KeychainHelper.shared.savedUsername,
              let password = KeychainHelper.shared.savedPassword
        else {
            throw AuthError.noSavedCredentials
        }
        return try await login(username: username, password: password)
    }

    // MARK: - Session identity

    /// Asks the server who this session is and persists the HMS user id.
    ///
    /// Works for the password JWT and the Keycloak token alike. On an SSO
    /// session it also moves the device-only history notes filed under the
    /// Keycloak `sub` by earlier builds to the user id (never merging).
    @discardableResult
    func resolveSessionIdentity() async throws -> String {
        let bootstrap: SessionBootstrapDTO = try await APIClient.shared.get(APIEndpoints.sessionBootstrap)
        guard let id = bootstrap.userId, !id.isEmpty else { throw APIError.unknown }
        KeychainHelper.shared.savedUserId = id
        if currentUser?.id != id {
            currentUser = bootstrap.user
        }
        KeychainHelper.shared.migrateHistoryNotesFromCurrentSubject(toUserId: id)
        return id
    }

    // MARK: - Logout

    /// Guards against re-entry. `refreshTokens()` calls `logout()` when it has
    /// no refresh token, and the old logout fired an authenticated request
    /// after the keychain was already emptied — 401, refresh, logout, repeat,
    /// for the lifetime of the process.
    private var isLoggingOut = false

    func logout() {
        guard !isLoggingOut else { return }
        isLoggingOut = true
        defer { isLoggingOut = false }

        // Captured BEFORE anything is cleared: the server can only revoke a
        // token it is shown, and the Keycloak refresh token lives in the
        // AppAuth state that `KeycloakAuthService.clear()` drops.
        let plan = SessionRevocationPlan.capture()

        pendingCredentials = nil
        PushManager.shared.sessionDidEnd()
        // clearSession(), not clearAll(): the user id must not outlive
        // sign-out (a stale id would resolve to the PREVIOUS patient and load
        // their conversations), but the saved username must survive or Face ID
        // is disabled for good.
        KeychainHelper.shared.clearSession()
        // `KeycloakAuthService.clear()` also clears the OIDC keychain entries;
        // keep logout delegating through the service so the two paths cannot drift.
        KeycloakAuthService.shared.clear()
        currentUser = nil
        isAuthenticated = false

        // Local sign-out is complete whatever happens next. The revocation is
        // one request each, with the captured token and no refresh path, so
        // a 401 on the way out cannot loop back here.
        Task {
            await SessionRevoker.revoke(plan)
        }
    }

    // MARK: - SSO (KC-3)

    /// Finishes a Keycloak login once `KeycloakAuthService.login(...)` resolves:
    /// resolves the HMS user id, then marks the session authenticated.
    ///
    /// A session whose user id cannot be resolved is not opened: chat and the
    /// history notes would treat the patient as signed out, which is the bug
    /// that kept SSO switched off. The Keycloak session is revoked instead.
    func completeSsoSession() async throws {
        guard KeychainHelper.shared.oidcAccessToken != nil else {
            isAuthenticated = false
            return
        }
        do {
            try await resolveSessionIdentity()
        } catch {
            logout()
            throw error
        }
        isAuthenticated = true
        PushManager.shared.sessionDidStart()
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
        case .noSavedCredentials: return "error_no_saved_credentials".localized
        case .biometricFailed: return "error_biometric_failed".localized
        case .notPatient: return "error_not_patient".localized
        }
    }
}

// MARK: - Request/Response Models (Auth-specific)

struct LoginRequest: Encodable {
    let username: String
    let password: String
    /// Only sent on the second call of a multi-role login; nil is omitted.
    let selectedRole: String?
}

struct RefreshTokenRequest: Encodable {
    let refreshToken: String
}

/// `POST /auth/logout`. The body is optional server-side; a nil token is
/// omitted, so an SSO session (which has no HMS refresh token) sends `{}`.
struct LogoutRequest: Encodable {
    let refreshToken: String?
}

/// `MfaController.MfaLoginVerifyRequest`.
struct MfaVerifyRequest: Encodable {
    let mfaToken: String
    let code: String
}

/// What the MFA step needs from the password step.
struct MfaChallenge: Equatable {
    let mfaToken: String
    /// False when the account has no authenticator yet; enrolment is done on
    /// the web portal, not in the app.
    let enrolled: Bool
    let username: String?
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

    // Gates that answer instead of a session (JwtResponse builder arms).
    let mfaRequired: Bool?
    let mfaEnrolled: Bool?
    let mfaToken: String?
    let roleSelectionRequired: Bool?
    let availableRoles: [String]?

    /// Build a UserDTO from the flat response fields
    var user: UserDTO {
        UserDTO(id: id, username: username, email: email,
                firstName: firstName, lastName: lastName,
                role: roleName ?? roles?.first,
                organizationId: nil, hospitalId: nil)
    }
}

/// `GET /auth/session/bootstrap` (`SessionBootstrapResponseDTO`), bare on the
/// wire. Only what the app uses is decoded.
struct SessionBootstrapDTO: Decodable {
    let userId: String?
    let username: String?
    let email: String?
    let firstName: String?
    let lastName: String?
    let roles: [String]?
    let patientId: String?
    let authSource: String?

    var user: UserDTO {
        UserDTO(id: userId, username: username, email: email,
                firstName: firstName, lastName: lastName,
                role: roles?.first,
                organizationId: nil, hospitalId: nil)
    }
}

/// `AuthController.ChangePasswordRequest`.
struct ChangePasswordRequest: Encodable {
    let currentPassword: String
    let newPassword: String
}

/// `PasswordResetRequestDTO`.
struct PasswordResetRequest: Encodable {
    let email: String
}

/// `PasswordResetConfirmDTO`.
struct PasswordResetConfirmRequest: Encodable {
    let token: String
    let newPassword: String
}

struct EmptyBody: Encodable {}
struct EmptyResponse: Decodable {}
