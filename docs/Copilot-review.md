hospital-portal/src/app/login/login.ts
Comment on lines +265 to +266
// Seed role context from JWT immediately so the bootstrap HTTP call
// (which includes an Authorization header) is authenticated.
Copilot AI
yesterday
The comment says the bootstrap call “includes an Authorization header”, but AuthService.sessionBootstrap() currently uses an absolute URL which bypasses apiPrefixInterceptor and won’t attach the bearer token. Either switch bootstrap to a relative URL or adjust the interceptor so same-origin absolute URLs still get auth headers.

Suggested change
// Seed role context from JWT immediately so the bootstrap HTTP call
// (which includes an Authorization header) is authenticated.
// Seed role context from JWT immediately so the UI has provisional
// role information before the session bootstrap response arrives.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/AuthBootstrapServiceImpl.java
@Transactional
public SessionBootstrapResponseDTO resolveCurrentSession(String username) {
User user = userRepository.findByUsername(username)
.orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
Copilot AI
yesterday
ResourceNotFoundException expects an i18n message key (it resolves via MessageUtil), but this passes a literal English message. This will surface as “[Missing translation] …” in responses/logs. Use an existing key like user.notFoundByUsername and pass username as the arg.

Suggested change
.orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
.orElseThrow(() -> new ResourceNotFoundException("user.notFoundByUsername", username));
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/AuthBootstrapServiceImpl.java

        // Side-effect: track most-recent OIDC login timestamp
        if (KEYCLOAK_AUTH_SOURCE.equals(user.getAuthSource())) {
            user.setLastOidcLoginAt(java.time.LocalDateTime.now());
Copilot AI
yesterday
lastOidcLoginAt is set with LocalDateTime.now() which uses the server’s local timezone. Since the API returns an Instant later, this can lead to inconsistent timestamps across environments. Prefer storing an Instant/OffsetDateTime in the entity for this column (and set it with Instant.now()), so the value is unambiguous.

Suggested change
user.setLastOidcLoginAt(java.time.LocalDateTime.now());
user.setLastOidcLoginAt(
java.time.LocalDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC));
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
scripts/seed-keycloak.ps1
}

$lookupUrl = '{0}/admin/realms/{1}/users?username={2}&exact=true' -f $BASE, $REALM, $Username
$u = Invoke-RestMethod -Uri $lookupUrl -Headers $H
Copilot AI
yesterday
After the create attempt, the script immediately queries .../users?username=... and indexes $u[0].id without checking that any user was returned. If user creation failed for reasons other than “already exists” (e.g. validation error), $u can be empty and this throws an unhelpful index error. Add a $u.Count -gt 0 check and fail with a clear message when the user can’t be resolved.

Suggested change
$u = Invoke-RestMethod -Uri $lookupUrl -Headers $H
$u = Invoke-RestMethod -Uri $lookupUrl -Headers $H
if ($null -eq $u -or $u.Count -le 0) {
throw "Unable to resolve Keycloak user '$Username' after create attempt. User creation may have failed; check the earlier error output for details."
}
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
scripts/seed-keycloak.local.json
Comment on lines +3 to +6
"baseUrl": "http://localhost:8081",
"realm": "hms",
"adminUser": "admin",
"adminPassword": "admin",
Copilot AI
yesterday
This file commits an admin password (and user passwords) into the repo. Even if intended for local dev, these values are easy to copy into shared environments by mistake. Consider replacing them with TODO placeholders and requiring env vars/CLI params (which the seeding script already supports), or provide an .example.json template instead.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/resources/db/migration/V56__keycloak_identity_link.sql
Comment on lines +23 to +25
-- Non-unique covering index for the bootstrap lookup path:
-- SELECT * FROM security.users WHERE keycloak_subject = $1
-- (index already covers the query; kept explicit for query-plan clarity)
Copilot AI
yesterday
The comment says this index is for lookups by keycloak_subject, but the actual index created is on auth_source. Either fix the comment to match the index purpose, or change the index to match the documented lookup path (note the unique index on keycloak_subject already covers subject lookups).

Suggested change
-- Non-unique covering index for the bootstrap lookup path:
-- SELECT * FROM security.users WHERE keycloak_subject = $1
-- (index already covers the query; kept explicit for query-plan clarity)
-- Non-unique index for lookups/filtering by authentication source:
-- SELECT * FROM security.users WHERE auth_source = $1
-- Supports queries that route users by trusted authentication path.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/AuthBootstrapServiceImpl.java
Comment on lines +116 to +118
Instant lastOidcLoginAt = user.getLastOidcLoginAt() != null
? user.getLastOidcLoginAt().toInstant(java.time.ZoneOffset.UTC)
: null;
Copilot AI
yesterday
Converting LocalDateTime to Instant via toInstant(ZoneOffset.UTC) assumes the stored value is UTC, but it was populated with LocalDateTime.now() (local time). This can shift the timestamp depending on server timezone. Either store the timestamp as Instant/OffsetDateTime, or convert using the intended zone consistently.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-portal/src/app/auth/auth.service.ts
Comment on lines +441 to +442
const baseUrl = this.isBrowser ? `${globalThis.location.origin}/api` : '/api';
return this.http.get<SessionBootstrapResponse>(`${baseUrl}/auth/session/bootstrap`);
Copilot AI
yesterday
sessionBootstrap() builds an absolute URL (${location.origin}/api/...). The apiPrefixInterceptor explicitly skips absolute URLs, so this request will not receive the Authorization header (and will likely 401 and always fall back). Use a relative URL (e.g. /auth/session/bootstrap) or update the interceptor to allow same-origin absolute URLs to go through the auth/header logic.

Suggested change
const baseUrl = this.isBrowser ? `${globalThis.location.origin}/api` : '/api';
return this.http.get<SessionBootstrapResponse>(`${baseUrl}/auth/session/bootstrap`);
return this.http.get<SessionBootstrapResponse>('/api/auth/session/bootstrap');