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
        static let userId = "com.bitnesttechs.hms.patient.userId"
        static let password = "com.bitnesttechs.hms.patient.password"
        // KC-3 — Keycloak OIDC
        static let oidcAuthState = "com.bitnesttechs.hms.patient.oidcAuthState"
        static let oidcAccessToken = "com.bitnesttechs.hms.patient.oidcAccessToken"
        static let oidcIdToken = "com.bitnesttechs.hms.patient.oidcIdToken"
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

    /// Persisted so the user id survives a relaunch. `AuthManager.currentUser`
    /// is only populated by `login(...)`, so anything deriving an id from it
    /// broke as soon as the app was reopened on a restored session.
    var savedUserId: String? {
        get { read(key: Keys.userId) }
        set { newValue == nil ? delete(key: Keys.userId) : save(newValue!, key: Keys.userId) }
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

    /// Serialized `OIDAuthState` (NSKeyedArchiver output). KC-3.
    var oidcAuthState: Data? {
        get { readData(key: Keys.oidcAuthState) }
        set {
            if let value = newValue {
                saveData(value, key: Keys.oidcAuthState)
            } else {
                delete(key: Keys.oidcAuthState)
            }
        }
    }

    /// Cached OIDC access token (mirrors OIDAuthState.lastTokenResponse.accessToken).
    var oidcAccessToken: String? {
        get { read(key: Keys.oidcAccessToken) }
        set { newValue == nil ? delete(key: Keys.oidcAccessToken) : save(newValue!, key: Keys.oidcAccessToken) }
    }

    /// Cached OIDC ID token (for end-session logout requests).
    var oidcIdToken: String? {
        get { read(key: Keys.oidcIdToken) }
        set { newValue == nil ? delete(key: Keys.oidcIdToken) : save(newValue!, key: Keys.oidcIdToken) }
    }

    /// Everything that identifies the SESSION, leaving the biometric
    /// credentials alone.
    ///
    /// `clearAll()` is for "forget this device entirely". Sign-out must not
    /// use it: it drops `savedUsername`, and `LoginViewModel` gates the Face
    /// ID button on that being present, so one sign-out disabled biometric
    /// login for the rest of the install.
    func clearSession() {
        delete(key: Keys.accessToken)
        delete(key: Keys.refreshToken)
        delete(key: Keys.userId)
        clearOidc()
    }

    func clearAll() {
        clearHistoryNotes()
        delete(key: Keys.accessToken)
        delete(key: Keys.refreshToken)
        delete(key: Keys.username)
        delete(key: Keys.password)
        // Without this the id outlives the session and the next person to
        // sign in on the device inherits the previous patient's chat threads.
        delete(key: Keys.userId)
        clearOidc()
    }

    func clearOidc() {
        delete(key: Keys.oidcAuthState)
        delete(key: Keys.oidcAccessToken)
        delete(key: Keys.oidcIdToken)
    }

    // MARK: - Personal notes on My Medical History

    /// The web keeps these notes encrypted in localStorage; here the
    /// Keychain is the device-only store, one bucket per patient so a
    /// relative sharing the phone never reads another patient's notes.
    ///
    /// The bucket is the patient's identity on whichever path signed them
    /// in: the `sub` of the stored Keycloak ID token, else the user id the
    /// password login persisted. Without either there is no bucket and the
    /// notes feature is hidden for the session; a shared fallback would
    /// pool every SSO patient's notes together.
    static let historyNoteSections = ["medical", "surgical", "family", "social"]
    private static let historyNotePrefix = "com.bitnesttechs.hms.patient.historyNote."

    var historyNoteOwner: String? {
        if let subject = Self.jwtSubject(oidcIdToken), !subject.isEmpty { return subject }
        if let id = savedUserId, !id.isEmpty { return id }
        return nil
    }

    private func historyNoteKey(_ section: String) -> String? {
        guard let owner = historyNoteOwner else { return nil }
        return Self.historyNotePrefix + owner + "." + section
    }

    func historyNote(section: String) -> String? {
        guard let key = historyNoteKey(section) else { return nil }
        return read(key: key)
    }

    /// An empty or blank note is removed, as the web does. Nothing is
    /// written without an owner.
    func setHistoryNote(_ value: String?, section: String) {
        guard let key = historyNoteKey(section) else { return }
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.isEmpty {
            delete(key: key)
        } else {
            save(trimmed, key: key)
        }
    }

    /// Every patient's notes on this device, not only the current one:
    /// `clearAll()` means "forget this device entirely".
    func clearHistoryNotes() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return }
        for item in items {
            if let account = item[kSecAttrAccount as String] as? String,
               account.hasPrefix(Self.historyNotePrefix) {
                delete(key: account)
            }
        }
    }

    /// The `sub` claim of a JWT, read locally: the payload is base64url
    /// JSON. No signature check is needed for a bucket name.
    private static func jwtSubject(_ token: String?) -> String? {
        guard let token else { return nil }
        let parts = token.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var payload = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json["sub"] as? String
    }

    // MARK: - Private Keychain operations

    @discardableResult
    private func save(_ value: String, key: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        return saveData(data, key: key)
    }

    @discardableResult
    private func saveData(_ data: Data, key: String) -> Bool {
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
        guard let data = readData(key: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func readData(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return data
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
