package com.example.hms.security.provider.confinement;

import java.util.List;

/**
 * What ANY provider user (a PHARMACY or LABORATORY facility in the caller's
 * permitted set) may reach, whatever the facility type (provider plan §6.4).
 * Everything else answers 404, exactly like an unmapped path.
 *
 * <p>Entries are per handler, method plus exact pattern. The wholesale
 * prefixes are a shorthand, not a hole: {@code ProviderConfinementCoverageTest}
 * freezes every handler under them in {@code provider-confinement/common.txt}
 * (and {@code patient-self-service.txt}), so a new handler under a prefix fails
 * the test until it is added there deliberately.
 *
 * <p>Only P1 edits this file. The facility-specific handlers live in
 * {@link PharmacyConfinement} (P2-PH) and {@link LaboratoryConfinement} (P2-LAB).
 */
public final class CommonProviderConfinement {

    /**
     * Handler patterns equal to, or under, these prefixes are reachable by
     * every provider user. {@code /auth}: login, refresh, logout, the session
     * bootstrap, MFA enrolment, the ws ticket and the caller's own credential
     * changes; the auth controllers are pre-tenant. {@code /notifications}: the
     * caller's own in-app notifications and preferences.
     */
    public static final List<String> WHOLESALE_PREFIXES = List.of("/auth", "/notifications");

    /**
     * Handlers under a wholesale prefix that a provider user may NOT reach:
     * the prefix is the caller's own notifications, and creating one for any
     * recipient is not that.
     */
    public static final List<ConfinementRule> WHOLESALE_EXCLUSIONS = List.of(
        new ConfinementRule("POST", "/notifications",
            "creates a notification for ANY recipient (an administrator's act); not the caller's own notifications"));

    public static final List<ConfinementRule> RULES = List.of(
        new ConfinementRule("POST", "/users/admin-register",
            "a PROVIDER_ADMIN registers its own staff through the provider registrar list (plan §6.3);"
                + " the service refuses every other provider role, any other facility and every PATIENT account"),
        new ConfinementRule("GET", "/me/assignments",
            "the caller's own assignments: the portal shell reads them to pick the acting facility"
                + " (ActingScopeResolver.SCOPE_ESTABLISHING_PATHS)"),
        new ConfinementRule("GET", "/me/dashboard-config",
            "the portal shell's own dashboard configuration; no patient data"),
        new ConfinementRule("GET", "/feature-flags",
            "the platform's feature flags the portal shell reads at start-up; no tenant or patient data"),
        new ConfinementRule("GET", "/users/{id}",
            "the plan's own-profile row (there is no /me/profile handler): the caller's OWN account only;"
                + " the confinement compares the {id} path variable with the caller's user id",
            "id"),
        new ConfinementRule("GET", "/provider/profile",
            "the caller's own facility profile (P1-T5); any staff member there, decided from live assignments"),
        new ConfinementRule("PUT", "/provider/profile",
            "the facility's operational contact (P1-T5); its PROVIDER_ADMIN only, live, never the verified identity"),
        new ConfinementRule("GET", "/provider/staff",
            "the facility's own staff (P1-T5); its PROVIDER_ADMIN only, live"),
        new ConfinementRule("POST", "/provider/staff/{userId}/deactivate",
            "retires a staff member's rows at the caller's OWN facility (P1-T5); never a peer admin or another facility"),
        new ConfinementRule("POST", "/provider/staff/{userId}/activate",
            "re-invites a staff member at the caller's OWN facility through a new code (P1-T5); never switches a row on"),
        new ConfinementRule("GET", "/provider/settings",
            "the shell's facility type and provider flags (P1-T9); no tenant or patient data"));

    /**
     * The only handlers a provider user reaches WITHOUT a second factor
     * (provider plan AC-13): signing in and out, the session bootstrap (which
     * reports {@code mfaEnrollmentRequired}), and MFA enrolment and challenge.
     * Every other request, whatever its handler (allowed, refused or
     * unmapped), answers 403 {@code mfa.enrollment.required}.
     *
     * <p>Narrower than the {@code /auth} prefix on purpose: the rest of
     * {@code /auth} changes the account itself (email, username, password,
     * recovery contacts, the MFA records behind {@code PUT /auth/credentials/mfa})
     * or opens the STOMP channel ({@code POST /auth/ws-ticket}), which a
     * password alone must not do. A new {@code /auth} handler needs the second
     * factor until it is added here, with its reason.
     */
    public static final List<ConfinementRule> SECOND_FACTOR_EXEMPT_RULES = List.of(
        new ConfinementRule("POST", "/auth/login", "signing in, which is how the second factor is presented"),
        new ConfinementRule("POST", "/auth/logout", "signing out"),
        new ConfinementRule("POST", "/auth/token/refresh",
            "the rotated tokens carry exactly the factor the refresh token had, never more"),
        new ConfinementRule("GET", "/auth/session/bootstrap", "reports mfaEnrollmentRequired to the portal"),
        new ConfinementRule("GET", "/auth/csrf-token", "the portal's CSRF bootstrap for the MFA forms"),
        new ConfinementRule("GET", "/auth/mfa/status", "whether the account has an authenticator yet"),
        new ConfinementRule("POST", "/auth/mfa/enroll",
            "first enrolment; replacing an existing authenticator needs the second factor (MfaController)"),
        new ConfinementRule("POST", "/auth/mfa/verify-enrollment", "confirms the new authenticator"),
        new ConfinementRule("POST", "/auth/mfa/verify", "the TOTP challenge, which mints the tokens carrying the factor"));

    /**
     * Paths served outside Spring MVC's request mappings, matched on the path
     * within the application: the health probe.
     */
    public static final List<ConfinementRule> NON_MVC_RULES = List.of(
        new ConfinementRule("GET", "/actuator/health", "liveness and readiness probe"));

    /**
     * Prefixes served outside Spring MVC: the SockJS/STOMP handshake. It
     * carries no data; every STOMP frame after it is held to the provider rule
     * by {@code WebSocketSubscriptionInterceptor}.
     */
    public static final List<String> NON_MVC_PREFIXES = List.of("/ws-chat");

    /**
     * Patient self-service, for a provider user who ALSO holds a PATIENT
     * assignment, whether that row is global or bound to the hospital that
     * registered them (PATIENT rows are outside the one-kind-of-facility rule
     * since #832). These handlers act on the caller's own record only.
     * {@code /me/patient} is a wholesale prefix, frozen handler by handler.
     */
    public static final List<String> PATIENT_SELF_SERVICE_PREFIXES = List.of("/me/patient");

    private static final String OWN_OPT_OUT = "/patients/{patientId}/record-sharing/opt-out";

    public static final List<ConfinementRule> PATIENT_SELF_SERVICE_RULES = List.of(
        new ConfinementRule("GET", OWN_OPT_OUT,
            "the patient's own sharing opt-out (portal and apps); no provider role is opt-out staff,"
                + " so the handler binds the caller to their own patient row"),
        new ConfinementRule("POST", OWN_OPT_OUT,
            "the patient records their own opt-out; bound to their own row as above"),
        new ConfinementRule("DELETE", OWN_OPT_OUT,
            "the patient revokes their own opt-out; bound to their own row as above"));

    private CommonProviderConfinement() {
    }
}
