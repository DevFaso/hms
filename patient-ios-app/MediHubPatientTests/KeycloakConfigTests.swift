import XCTest
@testable import MediHubPatient

/// KC-3 — smoke tests for the new Keycloak scaffolding. These run without a
/// live Keycloak; anything requiring the real issuer is gated behind
/// `MEDIHUB_KEYCLOAK_E2E=1` (mirrors the KC-2b Angular E2E pattern).
final class KeycloakConfigTests: XCTestCase {

    func testIsConfiguredFalseWhenIssuerBlank() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
        defer { unsetenv("MEDIHUB_KEYCLOAK_ISSUER") }
        XCTAssertFalse(KeycloakConfig.isConfigured)
    }

    func testRedirectURIFallbackWhenUnset() {
        unsetenv("MEDIHUB_KEYCLOAK_REDIRECT_URI")
        XCTAssertEqual(
            KeycloakConfig.redirectURI,
            "com.bitnesttechs.hms.patient.native:/oauth2redirect"
        )
    }

    func testClientIDFallbackWhenUnset() {
        unsetenv("MEDIHUB_KEYCLOAK_CLIENT_ID")
        XCTAssertEqual(KeycloakConfig.clientID, "hms-patient-ios")
    }
}

final class FeatureFlagsTests: XCTestCase {

    override func tearDown() {
        FeatureFlags.clearKeycloakSsoOverride()
        super.tearDown()
    }

    func testDefaultOffWhenNoIssuer() {
        setenv("MEDIHUB_KEYCLOAK_SSO_ENABLED", "1", 1)
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
        defer {
            unsetenv("MEDIHUB_KEYCLOAK_SSO_ENABLED")
            unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        }
        XCTAssertFalse(FeatureFlags.keycloakSsoEnabled)
    }

    func testRuntimeOverrideRespectsIssuerGate() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
        defer { unsetenv("MEDIHUB_KEYCLOAK_ISSUER") }
        FeatureFlags.setKeycloakSsoEnabled(true)
        // Issuer blank → flag still OFF even with runtime override ON
        XCTAssertFalse(FeatureFlags.keycloakSsoEnabled)
    }
}
