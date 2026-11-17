import Foundation

// MARK: - Server-side sign-out

/// Everything sign-out needs from the session, captured BEFORE the keychain
/// and the AppAuth state are cleared. Plain values, so it can outlive them.
struct SessionRevocationPlan: Equatable {
    /// The access token the session was using (OIDC first, as APIClient
    /// prefers it). Nil when there was no session at all.
    let bearer: String?
    /// The HMS refresh token of a password session, so `/auth/logout` can
    /// blacklist it alongside the access token.
    let hmsRefreshToken: String?
    /// The Keycloak refresh token of an SSO session. `/auth/logout` cannot
    /// revoke it, so it goes to Keycloak's revocation endpoint instead.
    let oidcRefreshToken: String?
    let keycloakIssuer: String?
    let keycloakClientId: String
    /// This install's push registration, removed before the token dies.
    let installationId: String

    @MainActor
    static func capture() -> SessionRevocationPlan {
        let keychain = KeychainHelper.shared
        let issuer = KeycloakConfig.issuer.trimmingCharacters(in: .whitespaces)
        return SessionRevocationPlan(
            bearer: keychain.oidcAccessToken ?? keychain.accessToken,
            hmsRefreshToken: keychain.refreshToken,
            oidcRefreshToken: KeycloakAuthService.shared.refreshToken,
            keycloakIssuer: issuer.isEmpty ? nil : issuer,
            keycloakClientId: KeycloakConfig.clientID,
            installationId: PushRegistration.installationId()
        )
    }
}

/// Revokes a session that has already been cleared locally. Every step is
/// best effort: sign-out never waits on, or fails because of, the network.
enum SessionRevoker {
    static func revoke(
        _ plan: SessionRevocationPlan,
        client: APIClient = .shared,
        session: URLSession = .shared
    ) async {
        if let bearer = plan.bearer, !bearer.isEmpty {
            // Push first, while the token is still valid: once /auth/logout
            // has blacklisted it, the DELETE would be refused and this phone
            // would keep receiving the previous patient's notifications.
            try? await client.sendNoContent(
                .DELETE,
                path: APIEndpoints.pushDevice(installationId: plan.installationId),
                auth: .bearer(bearer)
            )
            // An explicit bearer and no refresh-on-401: an expired token
            // simply fails here instead of looping back into logout().
            try? await client.post(
                APIEndpoints.logout,
                body: LogoutRequest(refreshToken: plan.hmsRefreshToken),
                bearer: bearer
            )
        }
        if let refresh = plan.oidcRefreshToken, !refresh.isEmpty,
           let issuer = plan.keycloakIssuer {
            await revokeKeycloakRefreshToken(refresh, issuer: issuer,
                                             clientId: plan.keycloakClientId, session: session)
        }
    }

    // MARK: Keycloak (RFC 7009)

    /// Posts the refresh token to the realm's `revocation_endpoint`, read from
    /// the discovery document (Keycloak's standard path when that fails).
    static func revokeKeycloakRefreshToken(
        _ token: String,
        issuer: String,
        clientId: String,
        session: URLSession
    ) async {
        let endpoint = await revocationEndpoint(issuer: issuer, session: session)
        guard let url = URL(string: endpoint) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = revocationBody(token: token, clientId: clientId)
        _ = try? await session.data(for: request)
    }

    static func revocationEndpoint(issuer: String, session: URLSession) async -> String {
        let trimmed = issuer.hasSuffix("/") ? String(issuer.dropLast()) : issuer
        let fallback = trimmed + "/protocol/openid-connect/revoke"
        guard let discovery = URL(string: trimmed + "/.well-known/openid-configuration"),
              let result = try? await session.data(from: discovery),
              let http = result.1 as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode),
              let json = try? JSONSerialization.jsonObject(with: result.0) as? [String: Any],
              let endpoint = json["revocation_endpoint"] as? String, !endpoint.isEmpty
        else { return fallback }
        return endpoint
    }

    /// `token=…&token_type_hint=refresh_token&client_id=…`, form-encoded.
    static func revocationBody(token: String, clientId: String) -> Data {
        var unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        unreserved.insert(charactersIn: "-._~")
        func encode(_ value: String) -> String {
            value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
        }
        let form = "token=\(encode(token))&token_type_hint=refresh_token&client_id=\(encode(clientId))"
        return Data(form.utf8)
    }
}
