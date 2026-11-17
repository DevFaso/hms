import XCTest
@testable import MediHubPatient

/// The request plumbing PR 4 added to `APIClient`: `Accept-Language` on every
/// request, a helper for 204 No Content, and a POST with an explicit bearer
/// that never enters the refresh-on-401 path.
final class APIClientTests: XCTestCase {

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
    }

    override func tearDown() {
        StubURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Accept-Language

    func testBuiltRequestCarriesTheGivenLanguage() throws {
        let request = try StubURLProtocol.client().makeRequest(.GET, path: "/me/patient/profile", language: "fr")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept-Language"), "fr")
    }

    func testBuiltRequestDefaultsToTheAppLanguage() throws {
        let restorer = LanguageRestorer()
        defer { restorer.restore() }
        LocalizationManager.shared.setLanguage("es")

        let request = try StubURLProtocol.client().makeRequest(.GET, path: "/me/patient/profile")

        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept-Language"), "es")
    }

    func testAcceptLanguageReachesTheWireOnEveryKindOfRequest() async throws {
        let restorer = LanguageRestorer()
        defer { restorer.restore() }
        LocalizationManager.shared.setLanguage("fr")
        StubURLProtocol.responder = { _ in (200, StubURLProtocol.json(["success": true, "data": ["x": 1]])) }
        let client = StubURLProtocol.client()

        let _: [String: Int] = try await client.request(.GET, path: "/a", requiresAuth: false)
        try await client.sendNoContent(.PUT, path: "/b", auth: .none)
        try await client.post("/c", body: EmptyBody(), bearer: "tok")
        _ = try await client.downloadFile("/d")

        XCTAssertEqual(StubURLProtocol.sent.count, 4)
        for sent in StubURLProtocol.sent {
            XCTAssertEqual(sent.headers["Accept-Language"], "fr", "\(sent.method) \(sent.path) lost Accept-Language")
        }
    }

    // MARK: - 204 No Content

    func testSendNoContentAcceptsAnEmpty204() async throws {
        StubURLProtocol.responder = { _ in (204, Data()) }
        try await StubURLProtocol.client().sendNoContent(.PUT, path: "/chat/mark-read/a/b", auth: .none)
        XCTAssertEqual(StubURLProtocol.sent.first?.method, "PUT")
        XCTAssertEqual(StubURLProtocol.sent.first?.path.hasSuffix("/chat/mark-read/a/b"), true)
    }

    func testDecodingRequestCannotReadAnEmpty204() async {
        // Why the helper exists: the decoding path has nothing to decode.
        StubURLProtocol.responder = { _ in (204, Data()) }
        do {
            let _: EmptyResponse = try await StubURLProtocol.client().request(.PUT, path: "/x", requiresAuth: false)
            XCTFail("an empty body should not decode")
        } catch {
            guard case APIError.decodingError = error else {
                return XCTFail("expected decodingError, got \(error)")
            }
        }
    }

    func testSendNoContentSurfacesTheServerMessageOnFailure() async {
        StubURLProtocol.responder = { _ in (404, StubURLProtocol.json(["message": "Introuvable"])) }
        do {
            try await StubURLProtocol.client().sendNoContent(.DELETE, path: "/x", auth: .none)
            XCTFail("a 404 must throw")
        } catch APIError.httpError(let status, let message) {
            XCTAssertEqual(status, 404)
            XCTAssertEqual(message, "Introuvable")
        } catch {
            XCTFail("expected httpError, got \(error)")
        }
    }

    // MARK: - Explicit bearer

    func testExplicitBearerIsSentAndA401IsNotRefreshed() async {
        StubURLProtocol.responder = { _ in (401, Data()) }
        do {
            try await StubURLProtocol.client().post("/auth/logout",
                                                    body: EmptyBody(),
                                                    bearer: "captured-token")
            XCTFail("a 401 must throw")
        } catch APIError.httpError(let status, _) {
            XCTAssertEqual(status, 401)
        } catch {
            XCTFail("expected httpError(401), got \(error)")
        }
        // One request: no /auth/token/refresh, no retry.
        XCTAssertEqual(StubURLProtocol.sent.count, 1)
        XCTAssertEqual(StubURLProtocol.sent.first?.headers["Authorization"], "Bearer captured-token")
    }

    // MARK: - Portal origin

    func testPortalOriginDropsTheApiHostPrefixOnly() {
        XCTAssertEqual(AppEnvironment.portalOrigin(forAssetOrigin: "https://api.e-keneya.com"), "https://e-keneya.com")
        XCTAssertEqual(AppEnvironment.portalOrigin(forAssetOrigin: "https://dev.e-keneya.com"), "https://dev.e-keneya.com")
    }
}
