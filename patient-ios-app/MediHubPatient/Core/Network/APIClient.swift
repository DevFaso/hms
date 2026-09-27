import Foundation

// MARK: - Environment

enum AppEnvironment {
    /// Available environments
    enum Environment: String, CaseIterable {
        // `api.dev.e-keneya.com` has no DNS record. The dev API is served
        // same-origin by the portal host, which is what the Angular dev
        // environment already targets.
        case dev = "https://dev.e-keneya.com/api"
        case prod = "https://api.e-keneya.com/api"
        case local = "http://localhost:8081/api"
    }

    /// Fallback when neither the scheme nor Info.plist supplies a base URL.
    ///
    /// Only `Release-Dev` and `Release-Prod` carry an xcconfig, so the plain
    /// `Release` configuration — what Product > Archive uses from Xcode — has
    /// no base URL at all. Defaulting that to dev would ship a
    /// distribution-signed build talking to the dev server, so the fallback
    /// follows the build type instead of being pinned to one environment.
    #if DEBUG
    static let current: Environment = .dev
    #else
    static let current: Environment = .prod
    #endif

    static var baseURL: String {
        // Scheme environment variable — local development only. It is empty
        // in an archive, which is why the Info.plist fallback below exists:
        // without it every TestFlight build silently used `current`.
        if let url = ProcessInfo.processInfo.environment["MEDIHUB_API_BASE_URL"],
           !url.isEmpty {
            return url
        }
        // Baked in per configuration by Config/{Dev,Prod}.xcconfig, the same
        // mechanism the MEDIHUB_KEYCLOAK_* settings already use.
        if let url = Bundle.main.object(forInfoDictionaryKey: "MEDIHUB_API_BASE_URL") as? String,
           !url.isEmpty {
            return url
        }
        return current.rawValue
    }
}

/// The origin behind the API base URL, for assets served outside `/api`.
///
/// Must strip only a TRAILING `/api`: `replacingOccurrences(of: "/api")`
/// also matched the `/api` inside `https://api.e-keneya.com`, turning the
/// production base URL into `https:/.e-keneya.com` and breaking every avatar.
extension AppEnvironment {
    static var assetOrigin: String {
        let base = baseURL
        return base.hasSuffix("/api") ? String(base.dropLast(4)) : base
    }

    /// Where the web portal's own pages live, for the flows the app hands
    /// over to the browser (a reset link opened from the e-mail).
    ///
    /// Dev serves the API same-origin under the portal host; prod serves it
    /// from an `api.` host in front of the portal's, so that prefix is dropped.
    static var portalOrigin: String {
        portalOrigin(forAssetOrigin: assetOrigin)
    }

    static func portalOrigin(forAssetOrigin origin: String) -> String {
        guard var components = URLComponents(string: origin),
              let host = components.host, host.hasPrefix("api.") else { return origin }
        components.host = String(host.dropFirst(4))
        return components.string ?? origin
    }
}

// MARK: - API Errors

/// Every description comes from `Localizable.strings`, so a French or Spanish
/// patient reads the error in the language of the screen around it. The one
/// exception is a server `message`, which the backend already localises from
/// the `Accept-Language` every request now carries.
enum APIError: LocalizedError {
    case invalidURL
    case unauthorized
    case httpError(statusCode: Int, message: String?)
    case decodingError(Error)
    case networkError(Error)
    case unknown

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "error_invalid_url".localized
        case .unauthorized:
            return "error_session_expired".localized
        case let .httpError(code, msg):
            if let msg, !msg.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return msg
            }
            return String(format: "error_server_code".localized, code)
        case .decodingError:
            // The decoder's own text is English and technical; it goes to the
            // debug log in decodeResponse, not to the patient.
            return "error_unreadable_response".localized
        case let .networkError(error):
            return Self.networkMessage(error)
        case .unknown:
            return "error_unknown".localized
        }
    }

    /// `URLError.localizedDescription` follows the DEVICE language, not the
    /// app's, so an English phone running the app in French read an English
    /// sentence here. The cases a patient can act on get their own text.
    static func networkMessage(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed:
                return "error_offline".localized
            case .timedOut:
                return "error_timeout".localized
            default:
                break
            }
        }
        return "error_network".localized
    }
}

// MARK: - API Response Wrapper

/// Matches the backend ApiResponseWrapper<T>
struct APIResponse<T: Decodable>: Decodable {
    let success: Bool
    let message: String?
    let data: T?
}

// MARK: - HTTP Method

enum HTTPMethod: String {
    case GET, POST, PUT, DELETE, PATCH
}

// MARK: - Request authorisation

/// Which bearer a request carries.
///
/// `.bearer` exists for sign-out: the token has to be captured BEFORE the
/// keychain is emptied, and a 401 on the way out must not enter the refresh
/// path (which calls `logout()` again). Nothing else should need it.
enum RequestAuth {
    /// No Authorization header.
    case none
    /// The signed-in session's token from the keychain, with one refresh and
    /// retry on 401.
    case session
    /// Exactly this token, and no refresh on 401.
    case bearer(String)
}

// MARK: - API Client

final class APIClient {
    /// `var` only so a unit test can swap in a client on a stub session and
    /// drive AuthManager's real sign-in paths; the app never reassigns it.
    static var shared = APIClient()

    private let session: URLSession

    /// Injectable so the unit tests can route requests through a stub
    /// `URLProtocol`; the app only ever uses `shared`.
    init(session: URLSession = APIClient.makeDefaultSession()) {
        self.session = session
    }

    static func makeDefaultSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }

    /// The app's language — `en`, `fr` or `es` — sent as `Accept-Language`
    /// on every request so the server's `message` fields come back in it.
    static var currentLanguage: String {
        LocalizationManager.shared.currentLanguage
    }

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .useDefaultKeys
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        d.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let str = try container.decode(String.self)
            let formats = [
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd",
            ]
            for fmt in formats {
                formatter.dateFormat = fmt
                if let date = formatter.date(from: str) { return date }
            }
            throw DecodingError.dataCorruptedError(in: container,
                                                   debugDescription: "Cannot decode date: \(str)")
        }
        return d
    }()

    // MARK: - Building a request

    /// URL, method, JSON headers, `Accept-Language` and body — everything but
    /// the bearer, which `execute` adds according to the `RequestAuth`.
    func makeRequest(
        _ method: HTTPMethod,
        path: String,
        body: Encodable? = nil,
        queryItems: [URLQueryItem]? = nil,
        language: String = APIClient.currentLanguage
    ) throws -> URLRequest {
        var components = URLComponents(string: AppEnvironment.baseURL + path)
        if let queryItems { components?.queryItems = queryItems }
        guard let url = components?.url else { throw APIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(language, forHTTPHeaderField: "Accept-Language")
        if let body {
            request.httpBody = try JSONEncoder().encode(body)
        }
        return request
    }

    // MARK: - Executing a request

    /// One round trip. A transport failure becomes `APIError.networkError`
    /// so its text is the app's own; a cancellation is rethrown untouched,
    /// because callers treat leaving a screen mid-load as "not a failure".
    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: request)
        } catch let urlError as URLError where urlError.code == .cancelled {
            throw urlError
        } catch let urlError as URLError {
            throw APIError.networkError(urlError)
        }
        guard let http = result.1 as? HTTPURLResponse else { throw APIError.unknown }
        return (result.0, http)
    }

    /// Prefers the Keycloak OIDC access token when a Keycloak session is
    /// active (KC-3), else the legacy username/password one. Returns whether
    /// the OIDC token was used, which decides how a 401 is refreshed.
    private func applySessionBearer(to request: inout URLRequest) -> Bool {
        if let oidc = KeychainHelper.shared.oidcAccessToken {
            request.setValue("Bearer \(oidc)", forHTTPHeaderField: "Authorization")
            return true
        }
        if let token = KeychainHelper.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return false
    }

    /// OIDC refresh is driven by AppAuth's `OIDAuthState.performAction`
    /// inside KeycloakAuthService; the legacy refresh flow only applies to
    /// the password-grant path.
    private func refreshSessionBearer(for request: inout URLRequest, usingOidc: Bool) async throws {
        if usingOidc {
            // `freshAccessToken()` returns `String?` and may throw, so `try?`
            // produces `String??`. Flatten so we correctly distinguish
            // "refresh succeeded with a token" from "no token".
            let refreshed = (try? await KeycloakAuthService.shared.freshAccessToken()) ?? nil
            guard let fresh = refreshed else {
                await AuthManager.shared.logout()
                throw APIError.unauthorized
            }
            request.setValue("Bearer \(fresh)", forHTTPHeaderField: "Authorization")
        } else {
            try await AuthManager.shared.refreshTokens()
            if let token = KeychainHelper.shared.accessToken {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
        }
    }

    /// Adds the bearer, sends, and — for a `.session` request only — refreshes
    /// once and retries on 401. `.none` and `.bearer` never refresh.
    func execute(_ request: URLRequest, auth: RequestAuth) async throws -> (Data, HTTPURLResponse) {
        var request = request
        var usingOidc = false
        var refreshable = false
        switch auth {
        case .none:
            break
        case .session:
            usingOidc = applySessionBearer(to: &request)
            refreshable = true
        case let .bearer(token):
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, http) = try await perform(request)
        guard http.statusCode == 401, refreshable else { return (data, http) }

        try await refreshSessionBearer(for: &request, usingOidc: usingOidc)
        let (retryData, retryHttp) = try await perform(request)
        if retryHttp.statusCode == 401 {
            await AuthManager.shared.logout()
            throw APIError.unauthorized
        }
        return (retryData, retryHttp)
    }

    // MARK: - Core request

    func request<T: Decodable>(
        _ method: HTTPMethod,
        path: String,
        body: Encodable? = nil,
        queryItems: [URLQueryItem]? = nil,
        requiresAuth: Bool = true
    ) async throws -> T {
        let request = try makeRequest(method, path: path, body: body, queryItems: queryItems)
        let (data, http) = try await execute(request, auth: requiresAuth ? .session : .none)
        return try decodeResponse(data, statusCode: http.statusCode)
    }

    /// For endpoints that answer 204 No Content (or a body nobody reads):
    /// success is any 2xx, failure is the usual `APIError`. `request<T>`
    /// cannot do this — an empty body fails to decode as any `T`.
    func sendNoContent(
        _ method: HTTPMethod,
        path: String,
        body: Encodable? = nil,
        queryItems: [URLQueryItem]? = nil,
        auth: RequestAuth = .session
    ) async throws {
        let request = try makeRequest(method, path: path, body: body, queryItems: queryItems)
        let (data, http) = try await execute(request, auth: auth)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw httpError(from: data, statusCode: http.statusCode)
        }
    }

    // MARK: - Decode helper

    /// The server's own message when the body carries one (the
    /// ApiResponseWrapper `message`, Spring's `fieldErrors` / `message`, or
    /// `error`), else nil and the localised "Server error (n)".
    private func httpError(from data: Data, statusCode: Int) -> APIError {
        // Try ApiResponseWrapper format: {"success":false,"message":"..."}
        var msg: String? = try? decoder.decode(APIResponse<EmptyData>.self, from: data).message

        // Try Spring validation/error format: {"message":"...","fieldErrors":{...}}
        if msg == nil, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let fieldErrors = json["fieldErrors"] as? [String: String] {
                // Combine field errors into readable message
                msg = fieldErrors.map { "\($0.key): \($0.value)" }.joined(separator: ", ")
            } else if let message = json["message"] as? String {
                msg = message
            } else if let error = json["error"] as? String {
                msg = error
            }
        }
        return APIError.httpError(statusCode: statusCode, message: msg)
    }

    private func decodeResponse<T: Decodable>(_ data: Data, statusCode: Int) throws -> T {
        guard (200 ..< 300).contains(statusCode) else {
            throw httpError(from: data, statusCode: statusCode)
        }

        // If caller wants raw Data (e.g. file downloads)
        if T.self == Data.self { return data as! T }

        do {
            // First try unwrapping ApiResponseWrapper<T>
            let wrapped = try decoder.decode(APIResponse<T>.self, from: data)
            if let result = wrapped.data { return result }
            // Some endpoints return the object directly (not wrapped)
            return try decoder.decode(T.self, from: data)
        } catch {
            // Fallback: decode directly
            do {
                return try decoder.decode(T.self, from: data)
            } catch let finalError {
                #if DEBUG
                print("[APIClient] decode failed for \(T.self): \(finalError)")
                #endif
                throw APIError.decodingError(finalError)
            }
        }
    }

    // MARK: - Convenience methods

    func get<T: Decodable>(_ path: String, queryItems: [URLQueryItem]? = nil) async throws -> T {
        try await request(.GET, path: path, queryItems: queryItems)
    }

    func post<T: Decodable>(_ path: String, body: Encodable? = nil, requiresAuth: Bool = true) async throws -> T {
        try await request(.POST, path: path, body: body, requiresAuth: requiresAuth)
    }

    /// POST with an explicitly supplied bearer and no refresh-on-401: sign-out
    /// sends `/auth/logout` with the token captured before the keychain was
    /// cleared, so the server can revoke it. The response body is not read.
    func post(_ path: String, body: Encodable? = nil, bearer: String) async throws {
        try await sendNoContent(.POST, path: path, body: body, auth: .bearer(bearer))
    }

    func put<T: Decodable>(_ path: String, body: Encodable? = nil) async throws -> T {
        try await request(.PUT, path: path, body: body)
    }

    func delete<T: Decodable>(_ path: String, queryItems: [URLQueryItem]? = nil) async throws -> T {
        try await request(.DELETE, path: path, queryItems: queryItems)
    }

    // MARK: - Multipart upload

    /// One file part plus optional text parts (`fields`), in that order. The
    /// text parts are what a Spring `@RequestPart("documentType")` /
    /// `@RequestParam` reads from a multipart body. Same bearer selection
    /// and one refresh on 401 as `request()`: a document upload that hits an
    /// expired token must retry with a fresh one, not surface as an error.
    func uploadMultipart<T: Decodable>(
        _ path: String,
        fileData: Data,
        fileName: String,
        mimeType: String,
        fieldName: String = "file",
        fields: [String: String] = [:]
    ) async throws -> T {
        var request = try makeRequest(.POST, path: path)
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        // The file name goes into a quoted header: quotes and CR/LF from a
        // user-chosen name would end the part early.
        let safeName = fileName
            .replacingOccurrences(of: "\"", with: "'")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")

        var body = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(safeName)\"\r\n".utf8))
        body.append(Data("Content-Type: \(mimeType)\r\n\r\n".utf8))
        body.append(fileData)
        body.append(Data("\r\n".utf8))
        for (name, value) in fields.sorted(by: { $0.key < $1.key }) {
            body.append(Data("--\(boundary)\r\n".utf8))
            body.append(Data("Content-Disposition: form-data; name=\"\(name)\"\r\n".utf8))
            body.append(Data("Content-Type: text/plain; charset=utf-8\r\n\r\n".utf8))
            body.append(Data(value.utf8))
            body.append(Data("\r\n".utf8))
        }
        body.append(Data("--\(boundary)--\r\n".utf8))
        request.httpBody = body

        // Same preference as request(): the OIDC token when a Keycloak
        // session is active, else the legacy one. Sending only the legacy
        // token made every avatar upload 401 under SSO.
        let (data, http) = try await execute(request, auth: .session)
        return try decodeResponse(data, statusCode: http.statusCode)
    }
}

// MARK: - File download

extension APIClient {
    /// Fetches raw bytes with the same bearer selection and one refresh on
    /// 401 as `request()`. Returns the bytes and the server's media type,
    /// which is what a previewer needs and what `request<Data>` discards.
    func downloadFile(_ path: String) async throws -> (data: Data, mimeType: String?) {
        var request = try makeRequest(.GET, path: path)
        request.setValue(nil, forHTTPHeaderField: "Content-Type")
        request.setValue("*/*", forHTTPHeaderField: "Accept")

        let (data, http) = try await execute(request, auth: .session)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw httpError(from: data, statusCode: http.statusCode)
        }
        // type/subtype only; parameters such as charset are not a media type.
        let mime = http.value(forHTTPHeaderField: "Content-Type")?
            .split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) }
        return (data, mime)
    }
}

// MARK: - Empty placeholder for decode errors

private struct EmptyData: Decodable {}
