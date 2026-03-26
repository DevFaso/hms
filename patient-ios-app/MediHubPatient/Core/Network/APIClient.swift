import Foundation

// MARK: - Environment

enum AppEnvironment {
    // Available environments
    enum Environment: String, CaseIterable {
        case dev  = "https://api.hms.dev.bitnesttechs.com/api"
        case uat  = "https://api.hms.uat.bitnesttechs.com/api"
        case prod = "https://api.hms.bitnesttechs.com/api"
        case local = "http://localhost:8081/api"
    }

    /// Current active environment — change here for quick switching
    static let current: Environment = .dev

    static var baseURL: String {
        // Override via Xcode scheme environment variable
        if let url = ProcessInfo.processInfo.environment["MEDIHUB_API_BASE_URL"] {
            return url
        }
        return current.rawValue
    }
}

// MARK: - API Errors

enum APIError: LocalizedError {
    case invalidURL
    case unauthorized
    case httpError(statusCode: Int, message: String?)
    case decodingError(Error)
    case networkError(Error)
    case unknown

    var errorDescription: String? {
        switch self {
        case .invalidURL:            return "Invalid URL"
        case .unauthorized:          return "Session expired. Please log in again."
        case .httpError(let code, let msg): return msg ?? "Server error (\(code))"
        case .decodingError(let e):  return "Data error: \(e.localizedDescription)"
        case .networkError(let e):   return e.localizedDescription
        case .unknown:               return "An unknown error occurred"
        }
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

// MARK: - API Client

final class APIClient {
    static let shared = APIClient()
    private init() {}

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()

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
                "yyyy-MM-dd"
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

    // MARK: - Core request

    func request<T: Decodable>(
        _ method: HTTPMethod,
        path: String,
        body: Encodable? = nil,
        queryItems: [URLQueryItem]? = nil,
        requiresAuth: Bool = true
    ) async throws -> T {
        // Build URL
        var components = URLComponents(string: AppEnvironment.baseURL + path)
        if let queryItems = queryItems { components?.queryItems = queryItems }
        guard let url = components?.url else { throw APIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        // Inject Bearer token
        if requiresAuth {
            if let token = KeychainHelper.shared.accessToken {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
        }

        // Encode body
        if let body = body {
            request.httpBody = try JSONEncoder().encode(body)
        }

        // Execute
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.unknown }

        // Handle 401 — attempt token refresh once
        if http.statusCode == 401 && requiresAuth {
            try await AuthManager.shared.refreshTokens()
            // Retry with new token
            if let token = KeychainHelper.shared.accessToken {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
            let (retryData, retryResponse) = try await session.data(for: request)
            guard let retryHttp = retryResponse as? HTTPURLResponse else { throw APIError.unknown }
            if retryHttp.statusCode == 401 {
                await AuthManager.shared.logout()
                throw APIError.unauthorized
            }
            return try decodeResponse(retryData, statusCode: retryHttp.statusCode)
        }

        return try decodeResponse(data, statusCode: http.statusCode)
    }

    // MARK: - Decode helper

    private func decodeResponse<T: Decodable>(_ data: Data, statusCode: Int) throws -> T {
        guard (200..<300).contains(statusCode) else {
            // Try to extract error message from backend ApiResponseWrapper
            let msg = try? decoder.decode(APIResponse<EmptyData>.self, from: data).message
            throw APIError.httpError(statusCode: statusCode, message: msg)
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

    func put<T: Decodable>(_ path: String, body: Encodable? = nil) async throws -> T {
        try await request(.PUT, path: path, body: body)
    }

    func delete<T: Decodable>(_ path: String, queryItems: [URLQueryItem]? = nil) async throws -> T {
        try await request(.DELETE, path: path, queryItems: queryItems)
    }

    // MARK: - Multipart upload

    func uploadMultipart<T: Decodable>(
        _ path: String,
        fileData: Data,
        fileName: String,
        mimeType: String,
        fieldName: String = "file"
    ) async throws -> T {
        var components = URLComponents(string: AppEnvironment.baseURL + path)
        guard let url = components?.url else { throw APIError.invalidURL }

        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if let token = KeychainHelper.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.unknown }
        return try decodeResponse(data, statusCode: http.statusCode)
    }
}

// MARK: - Empty placeholder for decode errors
private struct EmptyData: Decodable {}
