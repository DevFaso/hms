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

    public static final List<ConfinementRule> RULES = List.of(
        new ConfinementRule("POST", "/users/admin-register",
            "a PROVIDER_ADMIN registers its own staff through the provider registrar list (plan §6.3);"
                + " the service refuses every other provider role, any other facility and every PATIENT account"));

    /**
     * Paths served outside Spring MVC's request mappings, matched on the path
     * within the application: the health probe.
     */
    public static final List<ConfinementRule> NON_MVC_RULES = List.of(
        new ConfinementRule("GET", "/actuator/health", "liveness and readiness probe"));

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
