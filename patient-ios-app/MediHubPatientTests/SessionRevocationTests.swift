import XCTest
@testable import MediHubPatient

/// Sign-out revokes server side: the push registration is removed and
/// `/auth/logout` is sent with the token captured BEFORE the keychain was
/// cleared, each exactly once, and an SSO session's refresh token goes to
/// Keycloak's revocation endpoint.
final class SessionRevocationTests: XCTestCase {

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
    }

    override func tearDown() {
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func plan(bearer: String?, hmsRefresh: String?, oidcRefresh: String? = nil,
                      issuer: String? = nil) -> SessionRevocationPlan {
        SessionRevocationPlan(bearer: bearer, hmsRefreshToken: hmsRefresh, oidcRefreshToken: oidcRefresh,
                              keycloakIssuer: issuer, keycloakClientId: "hms-patient-ios",
                              installationId: "install-1")
    }

    func testPasswordSessionUnregistersPushThenLogsOutWithTheCapturedBearer() async {
        StubURLProtocol.responder = { _ in (200, Data()) }

        await SessionRevoker.revoke(plan(bearer: "access-1", hmsRefresh: "refresh-1"),
                                    client: StubURLProtocol.client(), session: StubURLProtocol.session())

        let sent = StubURLProtocol.sent
        XCTAssertEqual(sent.count, 2)
        XCTAssertEqual(sent.first?.method, "DELETE")
        XCTAssertEqual(sent.first?.path.hasSuffix("/me/push-devices/install-1"), true)
        XCTAssertEqual(sent.first?.headers["Authorization"], "Bearer access-1")

        XCTAssertEqual(sent.last?.method, "POST")
        XCTAssertEqual(sent.last?.path.hasSuffix("/auth/logout"), true)
        XCTAssertEqual(sent.last?.headers["Authorization"], "Bearer access-1")
        XCTAssertEqual(sent.last?.bodyJSON?["refreshToken"] as? String, "refresh-1")
    }

    func testFailuresAreNotRetriedAndNeverRefresh() async {
        // Both endpoints refuse (an expired token, a backend without the push
        // routes): still exactly one request each, no /auth/token/refresh.
        StubURLProtocol.responder = { request in
            request.url?.path.contains("push-devices") == true ? (404, Data()) : (401, Data())
        }

        await SessionRevoker.revoke(plan(bearer: "expired", hmsRefresh: nil),
                                    client: StubURLProtocol.client(), session: StubURLProtocol.session())

        XCTAssertEqual(StubURLProtocol.sent.map(\.method), ["DELETE", "POST"])
        XCTAssertFalse(StubURLProtocol.sent.contains { $0.path.contains("token/refresh") })
        // No refresh token for the body: `{}`, not a null field.
        XCTAssertEqual(StubURLProtocol.sent.last?.bodyJSON?.isEmpty, true)
    }

    func testSsoSessionAlsoRevokesTheKeycloakRefreshToken() async {
        StubURLProtocol.responder = { request in
            if request.url?.path.hasSuffix("/.well-known/openid-configuration") == true {
                return (200, StubURLProtocol.json(["revocation_endpoint": "https://kc.example/realms/hms/revoke-here"]))
            }
            return (200, Data())
        }

        await SessionRevoker.revoke(plan(bearer: "kc-access", hmsRefresh: nil, oidcRefresh: "kc/refresh+1",
                                         issuer: "https://kc.example/realms/hms"),
                                    client: StubURLProtocol.client(), session: StubURLProtocol.session())

        let revoke = StubURLProtocol.sent.last
        XCTAssertEqual(revoke?.url.absoluteString, "https://kc.example/realms/hms/revoke-here")
        XCTAssertEqual(revoke?.method, "POST")
        XCTAssertEqual(revoke?.headers["Content-Type"], "application/x-www-form-urlencoded")
        XCTAssertEqual(revoke?.bodyText,
                       "token=kc%2Frefresh%2B1&token_type_hint=refresh_token&client_id=hms-patient-ios")
    }

    func testRevocationEndpointFallsBackToKeycloaksStandardPath() async {
        StubURLProtocol.responder = { _ in (500, Data()) }
        let endpoint = await SessionRevoker.revocationEndpoint(issuer: "https://kc.example/realms/hms/",
                                                               session: StubURLProtocol.session())
        XCTAssertEqual(endpoint, "https://kc.example/realms/hms/protocol/openid-connect/revoke")
    }

    func testNoSessionSendsNothing() async {
        await SessionRevoker.revoke(plan(bearer: nil, hmsRefresh: nil),
                                    client: StubURLProtocol.client(), session: StubURLProtocol.session())
        XCTAssertTrue(StubURLProtocol.sent.isEmpty)
    }

    @MainActor
    func testCaptureTakesTheSsoTokensBeforeTheyAreCleared() {
        KeycloakAuthService.shared.acceptDebugSession(accessToken: "kc-acc", refreshToken: "kc-ref")
        defer { KeycloakAuthService.shared.clear() }

        let captured = SessionRevocationPlan.capture()

        XCTAssertEqual(captured.bearer, "kc-acc")
        XCTAssertEqual(captured.oidcRefreshToken, "kc-ref")
        XCTAssertEqual(captured.installationId, PushRegistration.installationId())
    }
}
