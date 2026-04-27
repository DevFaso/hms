import XCTest
@testable import MediHubPatient

/// KC-3 — smoke tests for the Keycloak scaffolding. These run without a
/// live Keycloak; anything requiring the real issuer is gated behind
/// `MEDIHUB_KEYCLOAK_E2E=1` (mirrors the KC-2b Angular E2E pattern).
///
/// Phase 2.8.B added `KeycloakRuntimeConfig` so the same code path works
/// for in-Xcode dev runs (ProcessInfo) and `xcodebuild archive` builds
/// (Bundle.main.infoDictionary, populated from per-env xcconfigs).
final class KeycloakConfigTests: XCTestCase {

    // Capture pre-test env values so tearDown can restore them and avoid
    // leaking mutations into other tests (parallel execution, custom schemes).
    private var savedIssuer: String?
    private var savedRedirectURI: String?
    private var savedClientID: String?

    override func setUp() {
        super.setUp()
        savedIssuer = Self.getenvString("MEDIHUB_KEYCLOAK_ISSUER")
        savedRedirectURI = Self.getenvString("MEDIHUB_KEYCLOAK_REDIRECT_URI")
        savedClientID = Self.getenvString("MEDIHUB_KEYCLOAK_CLIENT_ID")
        // Pin the Bundle fallback to an empty dict per-test so outcomes don't
        // depend on whatever the test runner's Info.plist happens to contain.
        KeycloakRuntimeConfig.infoDictionaryOverride = [:]
    }

    override func tearDown() {
        Self.restoreEnv("MEDIHUB_KEYCLOAK_ISSUER", savedIssuer)
        Self.restoreEnv("MEDIHUB_KEYCLOAK_REDIRECT_URI", savedRedirectURI)
        Self.restoreEnv("MEDIHUB_KEYCLOAK_CLIENT_ID", savedClientID)
        KeycloakRuntimeConfig.infoDictionaryOverride = nil
        super.tearDown()
    }

    private static func getenvString(_ name: String) -> String? {
        guard let raw = getenv(name) else { return nil }
        return String(cString: raw)
    }

    private static func restoreEnv(_ name: String, _ value: String?) {
        if let value = value {
            setenv(name, value, 1)
        } else {
            unsetenv(name)
        }
    }

    func testIsConfiguredFalseWhenIssuerBlank() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
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

    // MARK: - Bundle fallback (Phase 2.8.B)

    func testIssuerReadsFromBundleWhenProcessInfoEmpty() {
        unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        KeycloakRuntimeConfig.infoDictionaryOverride = [
            "MEDIHUB_KEYCLOAK_ISSUER": "https://hms-keycloak-uat.up.railway.app/realms/hms"
        ]
        XCTAssertEqual(
            KeycloakConfig.issuer,
            "https://hms-keycloak-uat.up.railway.app/realms/hms"
        )
        XCTAssertTrue(KeycloakConfig.isConfigured)
    }

    func testProcessInfoTakesPrecedenceOverBundle() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "https://process-info.example/realms/hms", 1)
        KeycloakRuntimeConfig.infoDictionaryOverride = [
            "MEDIHUB_KEYCLOAK_ISSUER": "https://bundle.example/realms/hms"
        ]
        XCTAssertEqual(KeycloakConfig.issuer, "https://process-info.example/realms/hms")
    }

    func testUnsubstitutedPlaceholderTreatedAsAbsent() {
        // When a build configuration doesn't override MEDIHUB_KEYCLOAK_ISSUER,
        // the `$(MEDIHUB_KEYCLOAK_ISSUER)` placeholder may survive into the
        // bundle. `KeycloakRuntimeConfig` must treat that as absent so callers
        // fall back to their own defaults rather than using the literal token.
        unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        unsetenv("MEDIHUB_KEYCLOAK_CLIENT_ID")
        KeycloakRuntimeConfig.infoDictionaryOverride = [
            "MEDIHUB_KEYCLOAK_ISSUER": "$(MEDIHUB_KEYCLOAK_ISSUER)",
            "MEDIHUB_KEYCLOAK_CLIENT_ID": "$(MEDIHUB_KEYCLOAK_CLIENT_ID)"
        ]
        XCTAssertEqual(KeycloakConfig.issuer, "")
        XCTAssertFalse(KeycloakConfig.isConfigured)
        XCTAssertEqual(KeycloakConfig.clientID, "hms-patient-ios")
    }
}

final class FeatureFlagsTests: XCTestCase {

    override func setUp() {
        super.setUp()
        KeycloakRuntimeConfig.infoDictionaryOverride = [:]
    }

    override func tearDown() {
        FeatureFlags.clearKeycloakSsoOverride()
        KeycloakRuntimeConfig.infoDictionaryOverride = nil
        unsetenv("MEDIHUB_KEYCLOAK_SSO_ENABLED")
        unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        super.tearDown()
    }

    func testDefaultOffWhenNoIssuer() {
        setenv("MEDIHUB_KEYCLOAK_SSO_ENABLED", "1", 1)
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
        XCTAssertFalse(FeatureFlags.keycloakSsoEnabled)
    }

    func testRuntimeOverrideRespectsIssuerGate() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "", 1)
        FeatureFlags.setKeycloakSsoEnabled(true)
        // Issuer blank → flag still OFF even with runtime override ON
        XCTAssertFalse(FeatureFlags.keycloakSsoEnabled)
    }

    func testEnabledFromBundleWhenProcessInfoEmpty() {
        unsetenv("MEDIHUB_KEYCLOAK_SSO_ENABLED")
        unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        KeycloakRuntimeConfig.infoDictionaryOverride = [
            "MEDIHUB_KEYCLOAK_SSO_ENABLED": "1",
            "MEDIHUB_KEYCLOAK_ISSUER": "https://hms-keycloak-uat.up.railway.app/realms/hms"
        ]
        XCTAssertTrue(FeatureFlags.keycloakSsoEnabled)
    }

    func testDisabledFromBundleEvenWithIssuer() {
        // Mirrors the Prod.xcconfig posture: URL pre-staged, SSO=0 until cutover.
        unsetenv("MEDIHUB_KEYCLOAK_SSO_ENABLED")
        unsetenv("MEDIHUB_KEYCLOAK_ISSUER")
        KeycloakRuntimeConfig.infoDictionaryOverride = [
            "MEDIHUB_KEYCLOAK_SSO_ENABLED": "0",
            "MEDIHUB_KEYCLOAK_ISSUER": "https://hms-keycloak-prod.up.railway.app/realms/hms"
        ]
        XCTAssertFalse(FeatureFlags.keycloakSsoEnabled)
    }
}
