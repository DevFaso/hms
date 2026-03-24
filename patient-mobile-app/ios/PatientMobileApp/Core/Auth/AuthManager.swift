import Foundation
import SwiftUI
import Combine

/// Manages authentication state across the app
@MainActor
final class AuthManager: ObservableObject {
    static let shared = AuthManager()

    @Published var isAuthenticated = false
    @Published var currentUser: UserInfo?
    @Published var isLoading = false
    @Published var requiresBiometric = false

    private let api = APIClient.shared
    private let keychain = KeychainService.shared
    private let biometric = BiometricService.shared
    private let inactivityTimeout: TimeInterval = 300 // 5 minutes
    private var backgroundTimestamp: Date?

    private var isBiometricEnabled: Bool {
        UserDefaults.standard.bool(forKey: "biometricEnabled")
    }

    private init() {}

    // MARK: - Login

    func login(username: String, password: String) async throws {
        isLoading = true
        defer { isLoading = false }

        let response = try await api.request(
            .login,
            body: LoginRequest(username: username, password: password),
            type: LoginResponse.self
        )

        let accessToken = response.accessToken ?? response.token ?? ""
        keychain.setAccessToken(accessToken)
        if let rt = response.refreshToken {
            keychain.setRefreshToken(rt)
        }

        currentUser = response.user
        isAuthenticated = true
    }

    // MARK: - Logout

    func logout() async {
        try? await api.request(.logout)
        keychain.clearTokens()
        currentUser = nil
        isAuthenticated = false
    }

    // MARK: - Session Restore

    func restoreSession() {
        if keychain.getAccessToken() != nil {
            if isBiometricEnabled && biometric.isAvailable {
                requiresBiometric = true
            } else {
                isAuthenticated = true
            }
        }
    }

    // MARK: - Biometric Unlock

    func authenticateWithBiometric() async -> Bool {
        do {
            let success = try await biometric.authenticate()
            if success {
                requiresBiometric = false
                isAuthenticated = true
            }
            return success
        } catch {
            return false
        }
    }

    func skipBiometric() {
        // Fall back to password — clear session so LoginView shows
        requiresBiometric = false
        isAuthenticated = false
        keychain.clearTokens()
    }

    // MARK: - Inactivity

    func recordBackgroundTimestamp() {
        backgroundTimestamp = Date()
    }

    func checkInactivityTimeout() {
        guard let bg = backgroundTimestamp else { return }
        if Date().timeIntervalSince(bg) > inactivityTimeout {
            Task { await logout() }
        }
        backgroundTimestamp = nil
    }
}

// MARK: - DTOs

struct LoginRequest: Encodable {
    let username: String
    let password: String
}

struct LoginResponse: Decodable {
    let accessToken: String?
    let refreshToken: String?
    let token: String?
    let user: UserInfo?
}

struct UserInfo: Decodable, Identifiable {
    let id: String
    let username: String
    let firstName: String?
    let lastName: String?
    let email: String?
    let roles: [String]?

    var displayName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }
}
