import Foundation
@testable import MediHubPatient

/// Answers every request of a stub `URLSession` from `responder`, and records
/// what was sent (method, URL, headers, body) for the test to assert on.
final class StubURLProtocol: URLProtocol {
    struct Sent {
        let method: String
        let url: URL
        let headers: [String: String]
        let body: Data?

        var path: String { url.path }
        var bodyJSON: [String: Any]? {
            guard let body else { return nil }
            return (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        }
        var bodyText: String { body.map { String(decoding: $0, as: UTF8.self) } ?? "" }
    }

    /// (status, body) for a request. Default: 200 with an empty body.
    static var responder: ((URLRequest) -> (Int, Data))?
    static var sent: [Sent] = []

    static func reset() {
        responder = nil
        sent = []
    }

    /// A session whose every request is answered here.
    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    static func client() -> APIClient {
        APIClient(session: session())
    }

    static func json(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        // URLSession hands a protocol the body as a stream, not httpBody.
        let body = request.httpBody ?? Self.read(request.httpBodyStream)
        Self.sent.append(Sent(method: request.httpMethod ?? "GET",
                              url: request.url ?? URL(string: "about:blank")!,
                              headers: request.allHTTPHeaderFields ?? [:],
                              body: body))
        let (status, data) = Self.responder?(request) ?? (200, Data())
        let response = HTTPURLResponse(url: request.url ?? URL(string: "about:blank")!,
                                       statusCode: status,
                                       httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream?) -> Data? {
        guard let stream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let size = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: size)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

/// Restores the app language after a test that switches it.
struct LanguageRestorer {
    let saved = LocalizationManager.shared.currentLanguage
    func restore() { LocalizationManager.shared.setLanguage(saved) }
}
