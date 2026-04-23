import Foundation

/// Runtime feature-flag store (KC-3).
///
/// Flags are additive and default OFF until the underlying feature is ready
/// (see `docs/tasks-keycloak.md`, prerequisite P-2). Defaults come from the
/// compile-time scheme environment variables; runtime overrides are persisted
/// in `UserDefaults` for QA / debug builds.
enum FeatureFlags {
    private static let keycloakSsoRuntimeKey = "feature_flag_keycloak_sso_enabled"

    /// `true` when the scheme exposes `MEDIHUB_KEYCLOAK_SSO_ENABLED=1` **and**
    /// the Keycloak issuer is non-empty. Runtime overrides via
    /// [setKeycloakSsoEnabled] take precedence.
    static var keycloakSsoEnabled: Bool {
        if let override = runtimeOverride { return override && KeycloakConfig.isConfigured }
        let env = ProcessInfo.processInfo.environment["MEDIHUB_KEYCLOAK_SSO_ENABLED"] ?? "0"
        let defaultOn = env == "1" || env.lowercased() == "true"
        return defaultOn && KeycloakConfig.isConfigured
    }

    static func setKeycloakSsoEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: keycloakSsoRuntimeKey)
    }

    static func clearKeycloakSsoOverride() {
        UserDefaults.standard.removeObject(forKey: keycloakSsoRuntimeKey)
    }

    private static var runtimeOverride: Bool? {
        guard UserDefaults.standard.object(forKey: keycloakSsoRuntimeKey) != nil else {
            return nil
        }
        return UserDefaults.standard.bool(forKey: keycloakSsoRuntimeKey)
    }
}

/// Compile-time + scheme-environment configuration for Keycloak SSO (KC-3).
enum KeycloakConfig {
    static var issuer: String {
        ProcessInfo.processInfo.environment["MEDIHUB_KEYCLOAK_ISSUER"] ?? ""
    }

    static var clientID: String {
        ProcessInfo.processInfo.environment["MEDIHUB_KEYCLOAK_CLIENT_ID"] ?? "hms-patient-ios"
    }

    static var redirectURI: String {
        ProcessInfo.processInfo.environment["MEDIHUB_KEYCLOAK_REDIRECT_URI"]
            ?? "com.bitnesttechs.hms.patient.native:/oauth2redirect"
    }

    static var isConfigured: Bool {
        !issuer.trimmingCharacters(in: .whitespaces).isEmpty
    }
}
