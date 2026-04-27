import Foundation

/// Runtime feature-flag store (KC-3).
///
/// Flags are additive and default OFF until the underlying feature is ready
/// (see `docs/tasks-keycloak.md`, prerequisite P-2). Defaults come from the
/// `MEDIHUB_KEYCLOAK_*` configuration values resolved by
/// `KeycloakRuntimeConfig`; runtime overrides are persisted in `UserDefaults`
/// for QA / debug builds.
enum FeatureFlags {
    private static let keycloakSsoRuntimeKey = "feature_flag_keycloak_sso_enabled"

    /// `true` when the resolved configuration exposes
    /// `MEDIHUB_KEYCLOAK_SSO_ENABLED=1` **and** the Keycloak issuer is
    /// non-empty. Runtime overrides via [setKeycloakSsoEnabled] take
    /// precedence over the resolved default.
    static var keycloakSsoEnabled: Bool {
        if let override = runtimeOverride { return override && KeycloakConfig.isConfigured }
        let raw = KeycloakRuntimeConfig.value("MEDIHUB_KEYCLOAK_SSO_ENABLED") ?? "0"
        let defaultOn = raw == "1" || raw.lowercased() == "true"
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

/// Compile-time + runtime configuration for Keycloak SSO (KC-3).
///
/// Values are resolved via `KeycloakRuntimeConfig`, which checks
/// `ProcessInfo` first (for in-Xcode dev runs and tests) and falls back to
/// `Bundle.main.infoDictionary` (for `xcodebuild archive` builds where
/// scheme env vars don't propagate). Defaults come from `project.yml`
/// `settings.base`; per-env values come from `Config/{Dev,UAT,Prod}.xcconfig`.
enum KeycloakConfig {
    static var issuer: String {
        KeycloakRuntimeConfig.value("MEDIHUB_KEYCLOAK_ISSUER") ?? ""
    }

    static var clientID: String {
        let resolved = KeycloakRuntimeConfig.value("MEDIHUB_KEYCLOAK_CLIENT_ID") ?? ""
        return resolved.isEmpty ? "hms-patient-ios" : resolved
    }

    static var redirectURI: String {
        let resolved = KeycloakRuntimeConfig.value("MEDIHUB_KEYCLOAK_REDIRECT_URI") ?? ""
        return resolved.isEmpty
            ? "com.bitnesttechs.hms.patient.native:/oauth2redirect"
            : resolved
    }

    static var isConfigured: Bool {
        !issuer.trimmingCharacters(in: .whitespaces).isEmpty
    }
}

/// Resolves `MEDIHUB_KEYCLOAK_*` values from the environment first, then
/// from `Bundle.main.infoDictionary`.
///
/// `ProcessInfo` covers Xcode Run / Test actions where scheme env vars are
/// injected. `Bundle.main.infoDictionary` covers `xcodebuild archive`
/// builds, where the values come from build settings substituted into
/// Info.plist (see `Config/README.md` and the
/// `MEDIHUB_KEYCLOAK_*` keys in Info.plist).
enum KeycloakRuntimeConfig {
    /// Optional override for tests. When non-nil, takes precedence over
    /// both `ProcessInfo` and `Bundle`. Reset to `nil` in `tearDown`.
    static var infoDictionaryOverride: [String: Any]?

    static func value(_ key: String) -> String? {
        if let env = ProcessInfo.processInfo.environment[key], !env.isEmpty {
            return env
        }
        let info = infoDictionaryOverride ?? Bundle.main.infoDictionary
        guard let raw = info?[key] as? String else { return nil }
        // Unsubstituted `$(VAR)` placeholders survive into the bundle when
        // a configuration doesn't define the build setting; treat them as
        // absent so callers can apply their own defaults.
        if raw.hasPrefix("$(") && raw.hasSuffix(")") { return nil }
        return raw
    }
}
