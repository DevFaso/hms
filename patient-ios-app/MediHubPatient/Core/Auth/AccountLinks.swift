import Foundation

/// Reads what a patient pastes from an account e-mail: either the whole link or
/// just the code. The link carries the token in its fragment
/// (`…/reset-password#token=…`, `…/verify#token=…`); a link sent before carried
/// it in the query (`…?token=…`, `…?email=…&token=…`).
enum AccountLinkParser {
    /// The `token` of a link carrying one (fragment first, then query), else the
    /// trimmed text itself (a code typed or pasted on its own).
    static func token(from text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let value = fragmentValue("token", in: trimmed), !value.isEmpty { return value }
        if let value = queryValue("token", in: trimmed), !value.isEmpty { return value }
        return trimmed
    }

    /// `name=value` inside the part of a link after `#`.
    private static func fragmentValue(_ name: String, in text: String) -> String? {
        guard let hash = text.firstIndex(of: "#") else { return nil }
        let fragment = text[text.index(after: hash)...]
        for pair in fragment.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2, String(parts[0]) == name {
                let raw = String(parts[1])
                return raw.removingPercentEncoding ?? raw
            }
        }
        return nil
    }

    /// The `email` query item of a pasted activation link, if any.
    static func email(from text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value = queryValue("email", in: trimmed), !value.isEmpty else { return nil }
        return value
    }

    private static func queryValue(_ name: String, in text: String) -> String? {
        guard text.contains("?"), let components = URLComponents(string: text) else { return nil }
        return components.queryItems?.first(where: { $0.name == name })?.value
    }

    /// Loose on purpose: the server is the judge. This only stops a request
    /// that cannot be an address at all.
    static func looksLikeEmail(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let at = trimmed.firstIndex(of: "@"), at != trimmed.startIndex else { return false }
        let domain = trimmed[trimmed.index(after: at)...]
        return domain.contains(".") && !domain.hasPrefix(".") && !domain.hasSuffix(".") && !trimmed.contains(" ")
    }
}

/// The rule the backend enforces on a new password (`AuthController`
/// change-password and the reset confirm): at least 8 characters.
enum PasswordRules {
    static let minimumLength = 8

    /// A localisation key for what is wrong, or nil when the pair is fine.
    static func problem(newPassword: String, confirmation: String, current: String? = nil) -> String? {
        if newPassword.count < minimumLength { return "password_min_length" }
        if newPassword != confirmation { return "passwords_mismatch" }
        if let current, !current.isEmpty, current == newPassword { return "password_must_differ" }
        return nil
    }
}

/// The account calls whose side effects on this device matter: what happens
/// to the password Face ID signs in with.
enum AccountService {
    /// `POST /auth/me/change-password` with an explicit bearer (no refresh:
    /// a wrong current password is a 401, which on the refreshing path would
    /// sign the patient out). On success the password Face ID replays is
    /// updated, so biometric sign-in keeps working.
    static func changePassword(current: String, new: String, bearer: String,
                               client: APIClient = .shared) async throws {
        try await client.sendNoContent(
            .POST,
            path: APIEndpoints.changePassword,
            body: ChangePasswordRequest(currentPassword: current, newPassword: new),
            auth: .bearer(bearer)
        )
        if KeychainHelper.shared.savedPassword != nil {
            KeychainHelper.shared.savedPassword = new
        }
    }

    /// `POST /auth/password/confirm`. The server answers 204 even for a bad
    /// token, so the app cannot know whether the password changed — and if it
    /// did, Face ID would keep replaying the old one until the lockout. The
    /// saved credentials are cleared on any 2xx; the next sign-in is typed.
    static func confirmPasswordReset(tokenText: String, newPassword: String,
                                     client: APIClient = .shared) async throws {
        try await client.sendNoContent(
            .POST,
            path: APIEndpoints.confirmPasswordReset,
            body: PasswordResetConfirmRequest(token: AccountLinkParser.token(from: tokenText),
                                              newPassword: newPassword),
            auth: .none
        )
        KeychainHelper.shared.clearSavedCredentials()
    }
}
