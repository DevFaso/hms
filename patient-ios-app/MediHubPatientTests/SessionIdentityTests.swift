import XCTest
@testable import MediHubPatient

/// Both login paths persist the HMS user id from `/auth/session/bootstrap`,
/// and the device-only history notes follow that id — including the one-time
/// move of notes an earlier build filed under the Keycloak `sub`.
@MainActor
final class SessionIdentityTests: XCTestCase {

    private var savedClient: APIClient!
    private var saved: [String: String?] = [:]

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
        savedClient = APIClient.shared
        APIClient.shared = StubURLProtocol.client()
        let keychain = KeychainHelper.shared
        saved = ["access": keychain.accessToken, "refresh": keychain.refreshToken,
                 "userId": keychain.savedUserId, "username": keychain.savedUsername,
                 "password": keychain.savedPassword]
        KeycloakAuthService.shared.clear()
    }

    override func tearDown() {
        let keychain = KeychainHelper.shared
        keychain.accessToken = saved["access"] ?? nil
        keychain.refreshToken = saved["refresh"] ?? nil
        keychain.savedUserId = saved["userId"] ?? nil
        keychain.savedUsername = saved["username"] ?? nil
        keychain.savedPassword = saved["password"] ?? nil
        KeycloakAuthService.shared.clear()
        AuthManager.shared.currentUser = nil
        AuthManager.shared.isAuthenticated = keychain.accessToken != nil
        APIClient.shared = savedClient
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func respond(bootstrapUserId: String) {
        StubURLProtocol.responder = { request in
            let path = request.url?.path ?? ""
            if path.hasSuffix("/auth/login") {
                return (200, StubURLProtocol.json([
                    "accessToken": "password-access", "refreshToken": "password-refresh",
                    "id": "login-body-id", "username": "awa", "roles": ["ROLE_PATIENT"],
                ]))
            }
            if path.hasSuffix("/auth/session/bootstrap") {
                return (200, StubURLProtocol.json([
                    "userId": bootstrapUserId, "username": "awa", "firstName": "Awa",
                    "lastName": "Traore", "roles": ["ROLE_PATIENT"], "authSource": "internal",
                ]))
            }
            return (204, Data())
        }
    }

    /// A JWT whose payload carries `sub`; the signature is never checked.
    private func idToken(sub: String) -> String {
        let payload = StubURLProtocol.json(["sub": sub]).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJub25lIn0." + payload + ".sig"
    }

    // MARK: - Password path

    func testPasswordLoginPersistsTheBootstrapUserId() async throws {
        respond(bootstrapUserId: "hms-user-password")

        let outcome = try await AuthManager.shared.login(username: "awa", password: "secret-1")

        XCTAssertEqual(outcome, .signedIn)
        XCTAssertEqual(KeychainHelper.shared.savedUserId, "hms-user-password")
        XCTAssertEqual(AuthManager.shared.currentUserId, "hms-user-password")
        XCTAssertTrue(StubURLProtocol.sent.contains { $0.path.hasSuffix("/auth/session/bootstrap") })
    }

    func testMfaChallengeIsReturnedBeforeThePatientGate() async throws {
        StubURLProtocol.responder = { _ in
            (200, StubURLProtocol.json(["mfaRequired": true, "mfaEnrolled": true,
                                        "mfaToken": "mfa-tok", "username": "awa"]))
        }

        let outcome = try await AuthManager.shared.login(username: "awa", password: "secret-1")

        XCTAssertEqual(outcome, .mfaRequired(MfaChallenge(mfaToken: "mfa-tok", enrolled: true, username: "awa")))
        AuthManager.shared.cancelMfa()
    }

    // MARK: - SSO path

    func testSsoSessionPersistsTheBootstrapUserId() async throws {
        respond(bootstrapUserId: "hms-user-sso")
        KeycloakAuthService.shared.acceptDebugSession(accessToken: "kc-access", idToken: idToken(sub: "kc-sub-1"))

        try await AuthManager.shared.completeSsoSession()

        XCTAssertTrue(AuthManager.shared.isAuthenticated)
        XCTAssertEqual(KeychainHelper.shared.savedUserId, "hms-user-sso")
        XCTAssertEqual(AuthManager.shared.currentUserId, "hms-user-sso")
        // The notes bucket is the HMS id, not the Keycloak sub.
        XCTAssertEqual(KeychainHelper.shared.historyNoteOwner, "hms-user-sso")
        let bootstrap = StubURLProtocol.sent.first { $0.path.hasSuffix("/auth/session/bootstrap") }
        XCTAssertEqual(bootstrap?.headers["Authorization"], "Bearer kc-access")
    }

    func testSsoNotesFiledUnderTheSubMoveToTheUserId() async throws {
        let keychain = KeychainHelper.shared
        keychain.setHistoryNote("old sso note", section: "medical", owner: "kc-sub-2")
        defer {
            for section in KeychainHelper.historyNoteSections {
                keychain.setHistoryNote(nil, section: section, owner: "kc-sub-2")
                keychain.setHistoryNote(nil, section: section, owner: "hms-user-sso-2")
            }
        }
        respond(bootstrapUserId: "hms-user-sso-2")
        KeycloakAuthService.shared.acceptDebugSession(accessToken: "kc-access", idToken: idToken(sub: "kc-sub-2"))

        try await AuthManager.shared.completeSsoSession()

        XCTAssertEqual(keychain.historyNote(section: "medical"), "old sso note")
        XCTAssertNil(keychain.historyNote(section: "medical", owner: "kc-sub-2"))
    }

    // MARK: - The migration rule itself

    func testMigrationNeverMergesIntoABucketThatHasNotes() {
        let keychain = KeychainHelper.shared
        let from = "sub-\(UUID().uuidString)", to = "user-\(UUID().uuidString)"
        defer {
            for section in KeychainHelper.historyNoteSections {
                keychain.setHistoryNote(nil, section: section, owner: from)
                keychain.setHistoryNote(nil, section: section, owner: to)
            }
        }
        keychain.setHistoryNote("from sub", section: "family", owner: from)
        keychain.setHistoryNote("already here", section: "social", owner: to)

        XCTAssertFalse(keychain.migrateHistoryNotes(from: from, to: to))
        XCTAssertEqual(keychain.historyNote(section: "family", owner: from), "from sub")
        XCTAssertNil(keychain.historyNote(section: "family", owner: to))
    }

    func testMigrationMovesEverySectionIntoAnEmptyBucket() {
        let keychain = KeychainHelper.shared
        let from = "sub-\(UUID().uuidString)", to = "user-\(UUID().uuidString)"
        defer {
            for section in KeychainHelper.historyNoteSections {
                keychain.setHistoryNote(nil, section: section, owner: from)
                keychain.setHistoryNote(nil, section: section, owner: to)
            }
        }
        keychain.setHistoryNote("m", section: "medical", owner: from)
        keychain.setHistoryNote("s", section: "surgical", owner: from)

        XCTAssertTrue(keychain.migrateHistoryNotes(from: from, to: to))
        XCTAssertEqual(keychain.historyNote(section: "medical", owner: to), "m")
        XCTAssertEqual(keychain.historyNote(section: "surgical", owner: to), "s")
        XCTAssertNil(keychain.historyNote(section: "medical", owner: from))
    }

    func testNotesOwnerIsTheUserIdEvenWithAnSsoIdToken() {
        KeychainHelper.shared.savedUserId = "hms-owner"
        KeycloakAuthService.shared.acceptDebugSession(accessToken: "a", idToken: idToken(sub: "kc-sub-owner"))
        XCTAssertEqual(KeychainHelper.shared.historyNoteOwner, "hms-owner")
    }
}
