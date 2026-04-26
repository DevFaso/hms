import AppAuth
import XCTest
@testable import MediHubPatient

/// KC-3 / Phase 2.3 (G-5) — Option A: exercise the post-redirect code path
/// (Keychain persistence, `hasActiveSession`) via the debug-only bypass that
/// substitutes for `ASWebAuthenticationSession`, which XCUITest cannot
/// automate. Mirrors the Android `LoginScreenSsoOnlyTest` in spirit:
/// validate the cutover-state behavior of the SSO code path without
/// fighting Apple's system browser sandbox.
///
/// Runs in the standard `MediHubPatientTests` bundle, no live realm or
/// docker-compose required. The mock-oauth2-server-backed integration
/// counterpart (Option B) lives in `KeycloakE2ETests` and is gated by
/// `MEDIHUB_KEYCLOAK_MOCK_E2E=1`.
@MainActor
final class KeycloakDebugBypassTests: XCTestCase {

    override func setUp() {
        super.setUp()
        // KeycloakAuthService is a @MainActor singleton backed by the
        // system Keychain — clear in setUp too so this suite is
        // order-independent against any prior test that may have left
        // a session behind (parallel execution, prior crash, etc.).
        KeycloakAuthService.shared.clear()
    }

    override func tearDown() {
        KeycloakAuthService.shared.clear()
        super.tearDown()
    }

    func testAcceptDebugSessionMarksSessionActive() {
        let service = KeycloakAuthService.shared
        XCTAssertFalse(
            service.hasActiveSession,
            "precondition: no SSO session before bypass is invoked"
        )

        service.acceptDebugSession(
            accessToken: "debug-access-token",
            refreshToken: "debug-refresh-token",
            idToken: "debug-id-token",
            expiresIn: 3600
        )

        XCTAssertTrue(
            service.hasActiveSession,
            "after the synthetic redirect AppAuth should report an authorized session"
        )
    }

    func testAcceptDebugSessionPersistsTokensToKeychain() {
        let service = KeycloakAuthService.shared
        service.acceptDebugSession(
            accessToken: "kc-access-xyz",
            refreshToken: "kc-refresh-xyz",
            idToken: "kc-id-xyz"
        )

        // Keychain mirroring is what `APIClient` reads to attach the
        // `Authorization: Bearer …` header — if these are missing the
        // post-cutover request path is broken.
        XCTAssertEqual(KeychainHelper.shared.oidcAccessToken, "kc-access-xyz")
        XCTAssertEqual(KeychainHelper.shared.oidcIdToken, "kc-id-xyz")
        XCTAssertNotNil(
            KeychainHelper.shared.oidcAuthState,
            "serialized OIDAuthState must be persisted so a relaunch can pick it up"
        )
    }

    func testClearRemovesActiveSession() {
        let service = KeycloakAuthService.shared
        service.acceptDebugSession(accessToken: "to-be-cleared")
        XCTAssertTrue(service.hasActiveSession)

        service.clear()

        XCTAssertFalse(service.hasActiveSession)
        XCTAssertNil(KeychainHelper.shared.oidcAccessToken)
        XCTAssertNil(KeychainHelper.shared.oidcAuthState)
    }
}
