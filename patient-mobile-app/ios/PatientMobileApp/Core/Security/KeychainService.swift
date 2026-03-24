import Foundation
import Security

/// Keychain-backed secure token storage
final class KeychainService {
    static let shared = KeychainService()

    private let service = "com.bitnesttechs.hms.patient"
    private let accessTokenKey = "access_token"
    private let refreshTokenKey = "refresh_token"

    private init() {}

    // MARK: - Access Token

    func getAccessToken() -> String? { read(key: accessTokenKey) }
    func setAccessToken(_ token: String) { save(key: accessTokenKey, value: token) }

    // MARK: - Refresh Token

    func getRefreshToken() -> String? { read(key: refreshTokenKey) }
    func setRefreshToken(_ token: String) { save(key: refreshTokenKey, value: token) }

    // MARK: - Clear

    func clearTokens() {
        delete(key: accessTokenKey)
        delete(key: refreshTokenKey)
    }

    // MARK: - Keychain Operations

    private func save(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        delete(key: key) // overwrite

        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String:   data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    private func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String:  true,
            kSecMatchLimit as String:  kSecMatchLimitOne,
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String:       kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
