import AppAuth
import XCTest
@testable import MediHubPatient

/// KC-3 / Phase 2.3 (G-5) — end-to-end coverage for the AppAuth wiring
/// and (when `MEDIHUB_KEYCLOAK_E2E=1`) live discovery against the local
/// docker-compose Keycloak.
///
/// Mirrors the Angular `KEYCLOAK_E2E=1` pattern noted in
/// [`docs/keycloak-live-testing.md`](../../../docs/keycloak-live-testing.md):
/// the always-on tests prove the AppAuth `OIDAuthorizationRequest` is
/// shaped correctly so config drift fails CI immediately; the gated
/// test reaches out to the realm's `.well-known/openid-configuration`
/// to verify connectivity and metadata shape end-to-end.
///
/// Run live coverage locally (with `docker compose --profile keycloak up`):
/// ```
/// MEDIHUB_KEYCLOAK_E2E=1 \
/// MEDIHUB_KEYCLOAK_ISSUER=http://localhost:8081/realms/hms \
/// xcodebuild test -scheme MediHubPatient -only-testing:MediHubPatientTests/KeycloakE2ETests
/// ```
final class KeycloakE2ETests: XCTestCase {

    private var savedIssuer: String?
    private var savedRedirectURI: String?
    private var savedClientID: String?

    override func setUp() {
        super.setUp()
        savedIssuer = Self.getenvString("MEDIHUB_KEYCLOAK_ISSUER")
        savedRedirectURI = Self.getenvString("MEDIHUB_KEYCLOAK_REDIRECT_URI")
        savedClientID = Self.getenvString("MEDIHUB_KEYCLOAK_CLIENT_ID")
    }

    override func tearDown() {
        Self.restoreEnv("MEDIHUB_KEYCLOAK_ISSUER", savedIssuer)
        Self.restoreEnv("MEDIHUB_KEYCLOAK_REDIRECT_URI", savedRedirectURI)
        Self.restoreEnv("MEDIHUB_KEYCLOAK_CLIENT_ID", savedClientID)
        super.tearDown()
    }

    // MARK: - Always-on: AppAuth request wiring (no network)

    /// Hard-pin the shape of the authorization request the app would send
    /// to Keycloak. Catches regressions where `client_id`, `redirect_uri`,
    /// or `scope` drift away from what the realm export expects.
    func testAuthorizationRequestCarriesExpectedClientAndRedirect() {
        setenv("MEDIHUB_KEYCLOAK_ISSUER", "http://localhost:8081/realms/hms", 1)
        setenv("MEDIHUB_KEYCLOAK_CLIENT_ID", "hms-patient-ios", 1)
        setenv(
            "MEDIHUB_KEYCLOAK_REDIRECT_URI",
            "com.bitnesttechs.hms.patient.native:/oauth2redirect",
            1
        )

        // Synthetic configuration so we don't hit the network. The fields
        // OIDAuthorizationRequest validates are clientId, redirectURL,
        // and scope; the endpoints don't matter for the assertions below.
        let config = OIDServiceConfiguration(
            authorizationEndpoint: URL(string: "http://localhost:8081/realms/hms/protocol/openid-connect/auth")!,
            tokenEndpoint: URL(string: "http://localhost:8081/realms/hms/protocol/openid-connect/token")!
        )

        let request = OIDAuthorizationRequest(
            configuration: config,
            clientId: KeycloakConfig.clientID,
            clientSecret: nil,
            scopes: [OIDScopeOpenID, OIDScopeProfile, OIDScopeEmail, "offline_access"],
            redirectURL: URL(string: KeycloakConfig.redirectURI)!,
            responseType: OIDResponseTypeCode,
            additionalParameters: nil
        )

        XCTAssertEqual(request.clientID, "hms-patient-ios")
        XCTAssertEqual(
            request.redirectURL?.absoluteString,
            "com.bitnesttechs.hms.patient.native:/oauth2redirect"
        )
        XCTAssertEqual(request.responseType, OIDResponseTypeCode)
        // PKCE verifier is generated lazily by AppAuth; just assert the
        // request ships with one configured (S256 by default).
        XCTAssertNotNil(request.codeVerifier, "AppAuth must auto-generate a PKCE verifier")
        XCTAssertNotNil(request.codeChallenge)
        // Scope is stored as a single space-joined string.
        XCTAssertNotNil(request.scope)
        XCTAssertTrue(request.scope!.contains(OIDScopeOpenID))
        XCTAssertTrue(request.scope!.contains(OIDScopeProfile))
        XCTAssertTrue(request.scope!.contains(OIDScopeEmail))
    }

    // MARK: - Gated: live discovery against docker-compose Keycloak

    /// Live-only — gated by `MEDIHUB_KEYCLOAK_E2E=1`. Requires
    /// `docker compose --profile keycloak up` to be running.
    func testLiveDiscoveryDocumentReachableAndWellFormed() throws {
        try XCTSkipUnless(
            Self.getenvString("MEDIHUB_KEYCLOAK_E2E") == "1",
            "Set MEDIHUB_KEYCLOAK_E2E=1 (and docker-compose Keycloak up) to run this spec."
        )

        let issuer = Self.getenvString("MEDIHUB_KEYCLOAK_ISSUER")
            ?? "http://localhost:8081/realms/hms"
        guard let url = URL(string: "\(issuer)/.well-known/openid-configuration") else {
            XCTFail("MEDIHUB_KEYCLOAK_ISSUER does not yield a parseable URL")
            return
        }

        let expectation = expectation(description: "openid-configuration fetch")
        var fetched: [String: Any]?
        var fetchError: Error?

        URLSession.shared.dataTask(with: url) { data, response, error in
            defer { expectation.fulfill() }
            if let error {
                fetchError = error
                return
            }
            // Surface non-200 status + a body snippet into fetchError so a
            // realm misconfiguration (e.g. wrong path, 401 from a guarded
            // endpoint) shows up in CI as a specific failure rather than a
            // generic "missing or unparseable" unwrap error.
            guard let http = response as? HTTPURLResponse else {
                fetchError = Self.testError(code: -1, "non-HTTP response")
                return
            }
            let bodySnippet = Self.bodySnippet(data)
            guard http.statusCode == 200 else {
                fetchError = Self.testError(
                    code: http.statusCode,
                    "HTTP \(http.statusCode) from discovery endpoint. Body: \(bodySnippet)"
                )
                return
            }
            guard let data,
                  let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else {
                fetchError = Self.testError(
                    code: http.statusCode,
                    "HTTP 200 but body was empty or not a JSON object. Body: \(bodySnippet)"
                )
                return
            }
            fetched = json
        }.resume()

        wait(for: [expectation], timeout: 10.0)

        XCTAssertNil(fetchError, "discovery fetch failed: \(String(describing: fetchError))")
        let json = try XCTUnwrap(
            fetched,
            "discovery document missing or unparseable. fetchError: \(String(describing: fetchError))"
        )
        // The three endpoints AppAuth needs at runtime — if any drift
        // away from the same issuer prefix the realm is misconfigured.
        for key in ["authorization_endpoint", "token_endpoint", "jwks_uri"] {
            let value = try XCTUnwrap(json[key] as? String, "missing \(key)")
            XCTAssertTrue(
                value.hasPrefix(issuer),
                "\(key) (\(value)) does not start with the configured issuer \(issuer)"
            )
        }
        // Realm must support PKCE S256, the only flow this app uses.
        let methods = json["code_challenge_methods_supported"] as? [String] ?? []
        XCTAssertTrue(
            methods.contains("S256"),
            "realm must advertise PKCE S256 support; got \(methods)"
        )
    }

    // MARK: - Helpers

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

    private static func testError(code: Int, _ message: String) -> NSError {
        NSError(
            domain: "KeycloakE2ETests",
            code: code,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    /// Render up to 500 bytes of the response body as a UTF-8 snippet so a
    /// realm-misconfig failure carries enough context to triage in CI.
    private static func bodySnippet(_ data: Data?) -> String {
        guard let data, !data.isEmpty else { return "<empty body>" }
        let body = String(data: data, encoding: .utf8) ?? "<non-UTF8 body: \(data.count) bytes>"
        return String(body.prefix(500))
    }
}
