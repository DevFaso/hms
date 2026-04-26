import AppAuth
import Foundation
import UIKit

/// Keycloak / OIDC auth façade backed by AppAuth-iOS (KC-3).
///
/// Additive — does not replace `AuthManager`. While
/// `FeatureFlags.keycloakSsoEnabled` is false (the default until prerequisite
/// P-2 lands) nothing in the app uses this class.
@MainActor
final class KeycloakAuthService: ObservableObject {
    static let shared = KeycloakAuthService()

    /// Held during the in-flight authorization so AppAuth can forward the
    /// redirect callback from `MediHubPatientApp.onOpenURL`.
    private var currentAuthFlow: OIDExternalUserAgentSession?

    /// Last successful auth state (also persisted to Keychain).
    @Published private(set) var authState: OIDAuthState?

    private init() {
        authState = Self.loadAuthState()
    }

    // MARK: - Public API

    var isConfigured: Bool { KeycloakConfig.isConfigured }

    /// True when a Keycloak session is active (used by APIClient).
    var hasActiveSession: Bool { authState?.isAuthorized == true }

    /// Discover OIDC endpoints, then run the Authorization Code + PKCE flow
    /// via `SFSafariViewController` / `ASWebAuthenticationSession`.
    func login(presenting viewController: UIViewController) async throws {
        guard isConfigured else { throw KeycloakError.notConfigured }
        guard let issuerURL = URL(string: KeycloakConfig.issuer),
              let redirectURL = URL(string: KeycloakConfig.redirectURI)
        else { throw KeycloakError.invalidConfiguration }

        let config = try await discoverConfiguration(issuer: issuerURL)
        let request = OIDAuthorizationRequest(
            configuration: config,
            clientId: KeycloakConfig.clientID,
            clientSecret: nil,
            scopes: [OIDScopeOpenID, OIDScopeProfile, OIDScopeEmail, "offline_access"],
            redirectURL: redirectURL,
            responseType: OIDResponseTypeCode,
            additionalParameters: nil
        )

        let state = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<OIDAuthState, Error>) in
            currentAuthFlow = OIDAuthState.authState(
                byPresenting: request,
                presenting: viewController
            ) { authState, error in
                if let authState {
                    continuation.resume(returning: authState)
                } else {
                    continuation.resume(throwing: error ?? KeycloakError.unknown)
                }
            }
        }

        currentAuthFlow = nil
        authState = state
        persist(state: state)
    }

    /// Forward a redirect URL from the app delegate / SwiftUI `onOpenURL`.
    @discardableResult
    func resume(url: URL) -> Bool {
        guard let flow = currentAuthFlow else { return false }
        let handled = flow.resumeExternalUserAgentFlow(with: url)
        if handled { currentAuthFlow = nil }
        return handled
    }

    /// Returns a non-expired access token (refreshing via the refresh token
    /// when necessary). Returns `nil` when no OIDC session is active.
    func freshAccessToken() async throws -> String? {
        guard let state = authState else { return nil }
        return try await withCheckedThrowingContinuation { continuation in
            state.performAction { accessToken, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    // Persist updated tokens (refresh may have rotated them).
                    Task { @MainActor in self.persist(state: state) }
                    continuation.resume(returning: accessToken)
                }
            }
        }
    }

    /// Clear the persisted OIDC session (matches `AuthManager.logout`).
    func clear() {
        authState = nil
        KeychainHelper.shared.clearOidc()
    }

    // MARK: - Private

    private func discoverConfiguration(issuer: URL) async throws -> OIDServiceConfiguration {
        try await withCheckedThrowingContinuation { continuation in
            OIDAuthorizationService.discoverConfiguration(forIssuer: issuer) { config, error in
                if let config {
                    continuation.resume(returning: config)
                } else {
                    continuation.resume(throwing: error ?? KeycloakError.discoveryFailed)
                }
            }
        }
    }

    private func persist(state: OIDAuthState) {
        let data: Data
        if #available(iOS 12.0, *) {
            data = (try? NSKeyedArchiver.archivedData(
                withRootObject: state,
                requiringSecureCoding: true
            )) ?? Data()
        } else {
            data = NSKeyedArchiver.archivedData(withRootObject: state)
        }
        KeychainHelper.shared.oidcAuthState = data
        KeychainHelper.shared.oidcAccessToken = state.lastTokenResponse?.accessToken
        KeychainHelper.shared.oidcIdToken = state.lastTokenResponse?.idToken
    }

    private static func loadAuthState() -> OIDAuthState? {
        guard let data = KeychainHelper.shared.oidcAuthState else { return nil }
        if #available(iOS 12.0, *) {
            return try? NSKeyedUnarchiver.unarchivedObject(
                ofClass: OIDAuthState.self,
                from: data
            )
        } else {
            return NSKeyedUnarchiver.unarchiveObject(with: data) as? OIDAuthState
        }
    }
}

// MARK: - Errors

enum KeycloakError: LocalizedError {
    case notConfigured
    case invalidConfiguration
    case discoveryFailed
    case unknown

    var errorDescription: String? {
        switch self {
        case .notConfigured: "Keycloak SSO is not configured for this build."
        case .invalidConfiguration: "Keycloak issuer/redirect URI is invalid."
        case .discoveryFailed: "Unable to reach the Keycloak discovery endpoint."
        case .unknown: "SSO login failed."
        }
    }
}

#if DEBUG
extension KeycloakAuthService {
    /// Debug-only bypass for the `ASWebAuthenticationSession` browser sheet.
    ///
    /// XCUITest cannot reliably automate `ASWebAuthenticationSession` (Apple
    /// runs it out-of-process and sandboxes it from the test runner), so we
    /// expose this entry point so test harnesses — and developers running a
    /// build with `MEDIHUB_KEYCLOAK_E2E_BYPASS=1` — can exercise the
    /// post-redirect code path (Keychain persistence, `hasActiveSession`,
    /// API client wiring) without driving the system browser. See
    /// `docs/keycloak-implementation-gaps.md` §4 (G-5) for the rationale.
    ///
    /// Synthesizes the same `OIDAuthState` shape AppAuth produces after a
    /// successful redirect, then routes through the existing `persist`
    /// path so the test exercises the real keychain flow.
    func acceptDebugSession(
        accessToken: String,
        refreshToken: String? = nil,
        idToken: String? = nil,
        expiresIn: TimeInterval = 3600
    ) {
        // Fall back to a docker-compose default when the build is unconfigured
        // (test harnesses, CI without scheme env vars). Never crash the host
        // process — assertion-fail and bail so the caller sees a no-op.
        let issuerString = KeycloakConfig.isConfigured
            ? KeycloakConfig.issuer
            : "http://localhost:8081/realms/hms"
        let redirectString = KeycloakConfig.redirectURI.isEmpty
            ? "com.bitnesttechs.hms.patient.native:/oauth2redirect"
            : KeycloakConfig.redirectURI
        let clientId = KeycloakConfig.clientID.isEmpty
            ? "hms-patient-ios"
            : KeycloakConfig.clientID

        guard
            let authEndpoint = URL(string: "\(issuerString)/protocol/openid-connect/auth"),
            let tokenEndpoint = URL(string: "\(issuerString)/protocol/openid-connect/token"),
            let redirectURL = URL(string: redirectString)
        else {
            assertionFailure("acceptDebugSession: KeycloakConfig produced an unparseable URL (issuer=\(issuerString), redirect=\(redirectString)).")
            return
        }
        let serviceConfig = OIDServiceConfiguration(
            authorizationEndpoint: authEndpoint,
            tokenEndpoint: tokenEndpoint
        )

        let authRequest = OIDAuthorizationRequest(
            configuration: serviceConfig,
            clientId: clientId,
            clientSecret: nil,
            scopes: [OIDScopeOpenID, OIDScopeProfile, OIDScopeEmail],
            redirectURL: redirectURL,
            responseType: OIDResponseTypeCode,
            additionalParameters: nil
        )

        let authResponseParams: [String: NSString] = [
            "code": "debug-auth-code" as NSString,
            "state": (authRequest.state ?? "debug-state") as NSString,
        ]
        let authResponse = OIDAuthorizationResponse(
            request: authRequest,
            parameters: authResponseParams
        )

        guard let tokenRequest = authResponse.tokenExchangeRequest() else {
            assertionFailure("AppAuth refused to build a token-exchange request from the synthetic auth response.")
            return
        }

        var tokenParams: [String: NSString] = [
            "access_token": accessToken as NSString,
            "token_type": "Bearer" as NSString,
            "expires_in": String(Int(expiresIn)) as NSString,
            "scope": "openid profile email" as NSString,
        ]
        if let refreshToken {
            tokenParams["refresh_token"] = refreshToken as NSString
        }
        if let idToken {
            tokenParams["id_token"] = idToken as NSString
        }
        let tokenResponse = OIDTokenResponse(request: tokenRequest, parameters: tokenParams)

        let state = OIDAuthState(
            authorizationResponse: authResponse,
            tokenResponse: tokenResponse
        )
        authState = state
        persist(state: state)
    }
}
#endif
