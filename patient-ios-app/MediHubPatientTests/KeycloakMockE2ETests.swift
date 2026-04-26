import AppAuth
import XCTest
@testable import MediHubPatient

/// KC-3 / Phase 2.3 (G-5) — Option B: end-to-end coverage of AppAuth's
/// discovery + token-exchange code paths against `mock-oauth2-server`,
/// gated by `MEDIHUB_KEYCLOAK_MOCK_E2E=1`.
///
/// Why mock-oauth2-server rather than the real Keycloak realm?
///   - The realm export does not enable Direct Access Grants on the iOS
///     client (correctly — patient flow is auth-code + PKCE only), so a
///     password grant against the real realm would be rejected.
///   - mock-oauth2-server accepts any `client_credentials` grant against
///     its `default` issuer, giving us a real OIDC-compliant token
///     response to exercise AppAuth's `OIDTokenRequest` flow.
///   - No realm config drift risk — we test the *AppAuth client* code
///     paths, not the realm.
///
/// The browser leg of the flow (`ASWebAuthenticationSession`) is
/// covered by Option A (`KeycloakDebugBypassTests`). See
/// `docs/keycloak-implementation-gaps.md` §4 for the full rationale.
///
/// Run locally:
/// ```
/// docker compose --profile mock-oidc up -d mock-oauth2-server
/// MEDIHUB_KEYCLOAK_MOCK_E2E=1 \
/// MEDIHUB_KEYCLOAK_MOCK_ISSUER=http://localhost:8082/default \
/// xcodebuild test -scheme MediHubPatient \
///   -only-testing:MediHubPatientTests/KeycloakMockE2ETests
/// ```
final class KeycloakMockE2ETests: XCTestCase {

    private var defaultIssuer: String {
        Self.getenvString("MEDIHUB_KEYCLOAK_MOCK_ISSUER")
            ?? "http://localhost:8082/default"
    }

    override func setUpWithError() throws {
        try XCTSkipUnless(
            Self.getenvString("MEDIHUB_KEYCLOAK_MOCK_E2E") == "1",
            "Set MEDIHUB_KEYCLOAK_MOCK_E2E=1 (and `docker compose --profile mock-oidc up -d mock-oauth2-server`) to run this spec."
        )
    }

    // MARK: - Discovery

    func testMockServerAdvertisesPKCEAndOpenIDConfiguration() throws {
        let url = try XCTUnwrap(
            URL(string: "\(defaultIssuer)/.well-known/openid-configuration"),
            "issuer URL must be parseable"
        )
        let json = try fetchJSON(url: url, timeout: 10)

        for key in ["authorization_endpoint", "token_endpoint", "jwks_uri"] {
            let value = try XCTUnwrap(json[key] as? String, "missing \(key)")
            XCTAssertTrue(
                value.contains("/default"),
                "\(key) (\(value)) does not appear to belong to the configured issuer"
            )
        }
        let methods = json["code_challenge_methods_supported"] as? [String] ?? []
        XCTAssertTrue(
            methods.contains("S256"),
            "mock issuer must advertise PKCE S256 (matches the realm contract); got \(methods)"
        )
    }

    // MARK: - Token exchange

    /// Exercises `OIDTokenRequest` against the mock token endpoint via the
    /// `client_credentials` grant. This is the same code path AppAuth runs
    /// after a successful redirect — minus the user agent — so it's the
    /// best signal we can get that the iOS client + AppAuth + the OIDC
    /// server all agree on the wire format.
    func testClientCredentialsGrantYieldsUsableAccessToken() throws {
        let configURL = try XCTUnwrap(
            URL(string: "\(defaultIssuer)/.well-known/openid-configuration")
        )
        let configJSON = try fetchJSON(url: configURL, timeout: 10)
        let tokenEndpoint = try XCTUnwrap(
            (configJSON["token_endpoint"] as? String).flatMap { URL(string: $0) },
            "discovery missing token_endpoint"
        )

        var request = URLRequest(url: tokenEndpoint)
        request.httpMethod = "POST"
        request.setValue(
            "application/x-www-form-urlencoded",
            forHTTPHeaderField: "Content-Type"
        )
        let body = [
            "grant_type=client_credentials",
            "client_id=ios-mock-client",
            "client_secret=any",
            "scope=openid",
        ].joined(separator: "&")
        request.httpBody = body.data(using: .utf8)

        let (data, response) = try synchronousDataTask(request: request, timeout: 10)
        let http = try XCTUnwrap(response as? HTTPURLResponse, "non-HTTP response")
        XCTAssertEqual(
            http.statusCode,
            200,
            "token endpoint returned \(http.statusCode); body: \(Self.bodySnippet(data))"
        )

        let json = try XCTUnwrap(
            (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any],
            "token response was not parseable JSON"
        )
        let accessToken = try XCTUnwrap(
            json["access_token"] as? String,
            "missing access_token in: \(json)"
        )
        XCTAssertFalse(accessToken.isEmpty, "access_token must be non-empty")
        XCTAssertEqual(json["token_type"] as? String, "Bearer")
    }

    // MARK: - Helpers

    private func fetchJSON(url: URL, timeout: TimeInterval) throws -> [String: Any] {
        let request = URLRequest(url: url)
        let (data, response) = try synchronousDataTask(request: request, timeout: timeout)
        let http = try XCTUnwrap(response as? HTTPURLResponse, "non-HTTP response")
        guard http.statusCode == 200 else {
            throw NSError(
                domain: "KeycloakMockE2ETests",
                code: http.statusCode,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "HTTP \(http.statusCode) from \(url.absoluteString). Body: \(Self.bodySnippet(data))",
                ]
            )
        }
        return try XCTUnwrap(
            (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any],
            "response body was not a JSON object"
        )
    }

    private func synchronousDataTask(
        request: URLRequest,
        timeout: TimeInterval
    ) throws -> (Data?, URLResponse?) {
        let expectation = expectation(description: "URLSession dataTask")
        var responseData: Data?
        var responseURLResponse: URLResponse?
        var responseError: Error?

        URLSession.shared.dataTask(with: request) { data, urlResponse, error in
            responseData = data
            responseURLResponse = urlResponse
            responseError = error
            expectation.fulfill()
        }.resume()

        wait(for: [expectation], timeout: timeout)
        if let error = responseError { throw error }
        return (responseData, responseURLResponse)
    }

    private static func getenvString(_ name: String) -> String? {
        guard let raw = getenv(name) else { return nil }
        return String(cString: raw)
    }

    private static func bodySnippet(_ data: Data?) -> String {
        guard let data, !data.isEmpty else { return "<empty body>" }
        let body = String(data: data, encoding: .utf8) ?? "<non-UTF8 body: \(data.count) bytes>"
        return String(body.prefix(500))
    }
}
