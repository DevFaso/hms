import Foundation

/// Reads what a patient pastes from an account e-mail: either the whole link
/// (`…/reset-password?token=…`, `…/verify?email=…&token=…`) or just the code.
enum AccountLinkParser {
    /// The `token` query item when the text is a link carrying one, else the
    /// trimmed text itself (a code typed or pasted on its own).
    static func token(from text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let value = queryValue("token", in: trimmed), !value.isEmpty { return value }
        return trimmed
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
