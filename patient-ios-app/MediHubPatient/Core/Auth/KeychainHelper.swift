import Foundation
import Security

// MARK: - KeychainHelper

// Stores/retrieves JWT tokens securely in the iOS Keychain.

final class KeychainHelper {
    static let shared = KeychainHelper()
    private init() {}

    private enum Keys {
        static let accessToken = "com.bitnesttechs.hms.patient.accessToken"
        static let refreshToken = "com.bitnesttechs.hms.patient.refreshToken"
        static let username = "com.bitnesttechs.hms.patient.username"
        static let password = "com.bitnesttechs.hms.patient.password"
    }

    // MARK: - Public accessors

    var accessToken: String? {
        get { read(key: Keys.accessToken) }
        set { newValue == nil ? delete(key: Keys.accessToken) : save(newValue!, key: Keys.accessToken) }
    }

    var refreshToken: String? {
        get { read(key: Keys.refreshToken) }
        set { newValue == nil ? delete(key: Keys.refreshToken) : save(newValue!, key: Keys.refreshToken) }
    }

    /// Stored for biometric re-auth (username only, password separately)
    var savedUsername: String? {
        get { read(key: Keys.username) }
        set { newValue == nil ? delete(key: Keys.username) : save(newValue!, key: Keys.username) }
    }

    /// Stored for biometric re-auth
    var savedPassword: String? {
        get { read(key: Keys.password) }
        set { newValue == nil ? delete(key: Keys.password) : save(newValue!, key: Keys.password) }
    }

    func clearAll() {
        delete(key: Keys.accessToken)
        delete(key: Keys.refreshToken)
        delete(key: Keys.username)
        delete(key: Keys.password)
    }

    // MARK: - Private Keychain operations

    @discardableResult
    private func save(_ value: String, key: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemDelete(query as CFDictionary)
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    private func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let str = String(data: data, encoding: .utf8) else { return nil }
        return str
    }

    @discardableResult
    private func delete(key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }
}
