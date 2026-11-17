import XCTest
@testable import MediHubPatient

/// Review round on the account flows: what happens to the Face ID password
/// after a password change or reset, and how an e-mail with `+` travels.
final class AccountServiceTests: XCTestCase {

    private var savedUsername: String?
    private var savedPassword: String?

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
        savedUsername = KeychainHelper.shared.savedUsername
        savedPassword = KeychainHelper.shared.savedPassword
    }

    override func tearDown() {
        KeychainHelper.shared.savedUsername = savedUsername
        KeychainHelper.shared.savedPassword = savedPassword
        StubURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Face ID credentials

    func testChangePasswordUpdatesTheFaceIdPassword() async throws {
        KeychainHelper.shared.savedUsername = "awa"
        KeychainHelper.shared.savedPassword = "old-password"
        StubURLProtocol.responder = { _ in (200, StubURLProtocol.json(["message": "ok"])) }

        try await AccountService.changePassword(current: "old-password", new: "new-password-1",
                                                bearer: "tok", client: StubURLProtocol.client())

        XCTAssertEqual(KeychainHelper.shared.savedPassword, "new-password-1")
        XCTAssertEqual(StubURLProtocol.sent.first?.headers["Authorization"], "Bearer tok")
    }

    func testARefusedChangeLeavesTheFaceIdPasswordAlone() async {
        KeychainHelper.shared.savedPassword = "old-password"
        StubURLProtocol.responder = { _ in (401, StubURLProtocol.json(["message": "Current password is incorrect."])) }

        do {
            try await AccountService.changePassword(current: "wrong", new: "new-password-1",
                                                    bearer: "tok", client: StubURLProtocol.client())
            XCTFail("a 401 must throw")
        } catch {}

        XCTAssertEqual(KeychainHelper.shared.savedPassword, "old-password")
        XCTAssertEqual(StubURLProtocol.sent.count, 1, "no refresh, no retry")
    }

    func testPasswordResetClearsTheFaceIdCredentials() async throws {
        KeychainHelper.shared.savedUsername = "awa"
        KeychainHelper.shared.savedPassword = "stale-password"
        StubURLProtocol.responder = { _ in (204, Data()) }

        try await AccountService.confirmPasswordReset(
            tokenText: "https://e-keneya.com/reset-password?token=abc123",
            newPassword: "brand-new-1", client: StubURLProtocol.client())

        XCTAssertNil(KeychainHelper.shared.savedPassword, "a stale password replayed by Face ID locks the account")
        XCTAssertNil(KeychainHelper.shared.savedUsername)
        XCTAssertEqual(StubURLProtocol.sent.first?.bodyJSON?["token"] as? String, "abc123")
    }

    // MARK: - '+' in an e-mail address

    func testPlusInAQueryValueIsPercentEncoded() throws {
        let request = try StubURLProtocol.client().makeRequest(
            .POST, path: "/auth/resend-verification",
            queryItems: [URLQueryItem(name: "email", value: "a+b@x.com")], language: "en")
        let query = try XCTUnwrap(request.url?.query)

        XCTAssertTrue(query.contains("a%2Bb"), "sent as \(query)")
        XCTAssertFalse(query.contains("+"), "a raw + is read as a space by the server: \(query)")
    }

    func testPlusSurvivesToTheWire() async throws {
        StubURLProtocol.responder = { _ in (200, StubURLProtocol.json(["message": "sent"])) }
        try await StubURLProtocol.client().sendNoContent(
            .GET, path: "/auth/verify-email",
            queryItems: [URLQueryItem(name: "email", value: "a+b@x.com"),
                         URLQueryItem(name: "token", value: "t+1&x=2")],
            auth: .none)

        let components = try XCTUnwrap(URLComponents(url: XCTUnwrap(StubURLProtocol.sent.first?.url),
                                                     resolvingAgainstBaseURL: false))
        let items = try XCTUnwrap(components.percentEncodedQueryItems)
        XCTAssertEqual(items.first { $0.name == "email" }?.value?.removingPercentEncoding, "a+b@x.com")
        XCTAssertTrue(items.first { $0.name == "email" }?.value?.contains("%2B") == true)
        XCTAssertEqual(items.first { $0.name == "token" }?.value?.removingPercentEncoding, "t+1&x=2")
    }
}
