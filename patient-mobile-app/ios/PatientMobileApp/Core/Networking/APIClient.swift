import Foundation

/// Central API client using URLSession — handles auth headers, token refresh, and response unwrapping
final class APIClient {
    static let shared = APIClient()

    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private let baseURL: URL
    private var isRefreshing = false
    private let refreshLock = NSLock()

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: config)

        self.decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        decoder.keyDecodingStrategy = .convertFromSnakeCase

        self.encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.keyEncodingStrategy = .convertToSnakeCase

        self.baseURL = AppEnvironment.current.baseURL
    }

    // MARK: - Public API

    /// Execute a typed request, returning decoded response
    func request<T: Decodable>(
        _ endpoint: APIEndpoint,
        body: (any Encodable)? = nil,
        type: T.Type
    ) async throws -> T {
        let data = try await execute(endpoint, body: body)
        // Unwrap ApiResponseWrapper if present
        if let wrapper = try? decoder.decode(APIResponseWrapper<T>.self, from: data) {
            return wrapper.data
        }
        return try decoder.decode(T.self, from: data)
    }

    /// Execute a request, discarding the response body
    func request(_ endpoint: APIEndpoint, body: (any Encodable)? = nil) async throws {
        _ = try await execute(endpoint, body: body)
    }

    // MARK: - Core Execution

    private func execute(_ endpoint: APIEndpoint, body: (any Encodable)? = nil) async throws -> Data {
        var urlRequest = try buildRequest(endpoint, body: body)

        // Attach access token if available
        if let token = KeychainService.shared.getAccessToken() {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await session.data(for: urlRequest)

        guard let http = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        // 401 → try refresh once, then retry
        if http.statusCode == 401 {
            let refreshed = try await refreshTokenIfNeeded()
            if refreshed {
                var retryRequest = try buildRequest(endpoint, body: body)
                if let newToken = KeychainService.shared.getAccessToken() {
                    retryRequest.setValue("Bearer \(newToken)", forHTTPHeaderField: "Authorization")
                }
                let (retryData, retryResponse) = try await session.data(for: retryRequest)
                guard let retryHttp = retryResponse as? HTTPURLResponse else {
                    throw APIError.invalidResponse
                }
                try validateStatus(retryHttp, data: retryData)
                return retryData
            } else {
                throw APIError.unauthorized
            }
        }

        try validateStatus(http, data: data)
        return data
    }

    // MARK: - Request Building

    private func buildRequest(_ endpoint: APIEndpoint, body: (any Encodable)? = nil) throws -> URLRequest {
        guard let url = URL(string: endpoint.path, relativeTo: baseURL) else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if let body {
            request.httpBody = try encoder.encode(AnyEncodable(body))
        }

        return request
    }

    // MARK: - Token Refresh

    private func refreshTokenIfNeeded() async throws -> Bool {
        refreshLock.lock()
        if isRefreshing {
            refreshLock.unlock()
            try await Task.sleep(nanoseconds: 500_000_000) // wait 0.5s
            return KeychainService.shared.getAccessToken() != nil
        }
        isRefreshing = true
        refreshLock.unlock()

        defer {
            refreshLock.lock()
            isRefreshing = false
            refreshLock.unlock()
        }

        guard let refreshToken = KeychainService.shared.getRefreshToken() else {
            return false
        }

        let body = ["refreshToken": refreshToken]

        guard let url = URL(string: APIEndpoint.refreshToken.path, relativeTo: baseURL) else {
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)

        let (data, response) = try await session.data(for: request)

        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            KeychainService.shared.clearTokens()
            return false
        }

        let tokens = try decoder.decode(TokenResponse.self, from: data)
        KeychainService.shared.setAccessToken(tokens.accessToken)
        if let rt = tokens.refreshToken {
            KeychainService.shared.setRefreshToken(rt)
        }
        return true
    }

    // MARK: - Validation

    private func validateStatus(_ response: HTTPURLResponse, data: Data) throws {
        switch response.statusCode {
        case 200...299:
            return
        case 401:
            throw APIError.unauthorized
        case 403:
            throw APIError.forbidden
        case 404:
            throw APIError.notFound
        case 409:
            throw APIError.conflict
        case 422:
            if let detail = try? decoder.decode(ValidationError.self, from: data) {
                throw APIError.validation(detail.errors)
            }
            throw APIError.validation([:])
        case 500...599:
            throw APIError.server(response.statusCode)
        default:
            throw APIError.unknown(response.statusCode)
        }
    }
}

// MARK: - Supporting Types

struct APIResponseWrapper<T: Decodable>: Decodable {
    let status: String?
    let message: String?
    let data: T
}

struct TokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String?
    let token: String?

    var resolvedAccessToken: String { accessToken.isEmpty ? (token ?? "") : accessToken }
}

struct ValidationError: Decodable {
    let errors: [String: String]
}

enum APIError: LocalizedError {
    case invalidURL
    case invalidResponse
    case unauthorized
    case forbidden
    case notFound
    case conflict
    case validation([String: String])
    case server(Int)
    case unknown(Int)

    var errorDescription: String? {
        switch self {
        case .invalidURL:       return "Invalid URL"
        case .invalidResponse:  return "Invalid server response"
        case .unauthorized:     return "Session expired. Please log in again."
        case .forbidden:        return "You don't have permission for this action."
        case .notFound:         return "Resource not found"
        case .conflict:         return "A conflict occurred"
        case .validation(let e):return "Validation failed: \(e.values.joined(separator: ", "))"
        case .server(let code): return "Server error (\(code))"
        case .unknown(let code):return "Unexpected error (\(code))"
        }
    }
}

/// Type-erased Encodable wrapper
private struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void

    init(_ value: any Encodable) {
        _encode = { try value.encode(to: $0) }
    }

    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}
